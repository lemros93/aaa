package com.yourcompany.importer.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.importer.model.*;
import com.yourcompany.importer.plugin.StepExtra;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

public class NdjsonParser {

    private static final Logger log = LoggerFactory.getLogger(NdjsonParser.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public TestRun parse(String filePath, Map<String, StepExtra> stepExtras) throws IOException {
        log.info("Parsing NDJSON file: {}", filePath);

        // Maps to correlate messages by their IDs
        Map<String, String>  pickleIdToName          = new HashMap<>(); // pickleId -> scenario name
        Map<String, String>  pickleIdToFeatureId     = new HashMap<>(); // pickleId -> uri
        Map<String, String>  pickleStepIdToText      = new HashMap<>(); // pickleStepId -> step text
        Map<String, String>  pickleStepIdToKeyword   = new HashMap<>(); // pickleStepId -> keyword
        Map<String, String>  testStepIdToPickleStepId = new HashMap<>();// testStepId -> pickleStepId
        Map<String, String>  testCaseIdToPickleId    = new HashMap<>(); // testCaseId -> pickleId
        Map<String, String>  testCaseStartedToCase   = new HashMap<>(); // testCaseStartedId -> testCaseId
        Map<String, Feature>  featureMap             = new LinkedHashMap<>(); // uri -> Feature
        Map<String, Scenario> scenarioMap            = new LinkedHashMap<>(); // testCaseStartedId -> Scenario
        Map<String, List<Step>> stepsMap             = new LinkedHashMap<>(); // testCaseStartedId -> steps

        long earliestTimestamp = Long.MAX_VALUE;

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                JsonNode root = mapper.readTree(line);

                // --- Source (feature file info) ---
                if (root.has("source")) {
                    JsonNode source = root.get("source");
                    String uri  = source.path("uri").asText();
                    Feature f   = new Feature();
                    f.filePath  = uri;
                    f.name      = extractFeatureName(uri);
                    featureMap.put(uri, f);
                }

                // --- GherkinDocument (contains feature name) ---
                if (root.has("gherkinDocument")) {
                    JsonNode doc     = root.get("gherkinDocument");
                    String uri       = doc.path("uri").asText();
                    JsonNode feature = doc.path("feature");
                    if (!feature.isMissingNode()) {
                        Feature f = featureMap.computeIfAbsent(uri, u -> {
                            Feature nf = new Feature();
                            nf.filePath = u;
                            return nf;
                        });
                        f.name = feature.path("name").asText(f.name);
                    }
                }

                // --- Pickle (scenario + step definitions) ---
                if (root.has("pickle")) {
                    JsonNode pickle = root.get("pickle");
                    String pickleId = pickle.path("id").asText();
                    pickleIdToName.put(pickleId, pickle.path("name").asText());
                    pickleIdToFeatureId.put(pickleId, pickle.path("uri").asText());

                    // step text is here — pickleStepId -> text + keyword
                    for (JsonNode ps : pickle.path("steps")) {
                        String psId    = ps.path("id").asText();
                        String text    = ps.path("text").asText();
                        // keyword is in gherkinDocument, pickle only has "type" (CONTEXT/ACTION/OUTCOME)
                        // we store type as fallback keyword here, overridden below from gherkinDocument
                        pickleStepIdToText.put(psId, text);
                    }
                }

                // --- GherkinDocument steps — keyword (Given/When/Then) is here ---
                if (root.has("gherkinDocument")) {
                    JsonNode doc = root.get("gherkinDocument");
                    extractKeywordsFromGherkin(doc.path("feature"), pickleStepIdToKeyword);
                }

                // --- TestCase — maps testStepId -> pickleStepId ---
                if (root.has("testCase")) {
                    JsonNode tc     = root.get("testCase");
                    String tcId     = tc.path("id").asText();
                    String pickleId = tc.path("pickleId").asText();
                    testCaseIdToPickleId.put(tcId, pickleId);

                    for (JsonNode ts : tc.path("testSteps")) {
                        String testStepId   = ts.path("id").asText();
                        String pickleStepId = ts.path("pickleStepId").asText();
                        if (!pickleStepId.isBlank()) {
                            testStepIdToPickleStepId.put(testStepId, pickleStepId);
                        }
                    }
                }

                // --- TestCaseStarted ---
                if (root.has("testCaseStarted")) {
                    JsonNode tcs = root.get("testCaseStarted");
                    String tcsId = tcs.path("id").asText();
                    String tcId  = tcs.path("testCaseId").asText();
                    testCaseStartedToCase.put(tcsId, tcId);

                    long ts = getTimestampMs(tcs.path("timestamp"));
                    if (ts < earliestTimestamp) earliestTimestamp = ts;

                    String pickleId   = testCaseIdToPickleId.get(tcId);
                    Scenario scenario = new Scenario();
                    scenario.name     = pickleId != null
                            ? pickleIdToName.getOrDefault(pickleId, "Unknown") : "Unknown";
                    scenarioMap.put(tcsId, scenario);
                    stepsMap.put(tcsId, new ArrayList<>());
                }

                // --- TestStepFinished ---
                if (root.has("testStepFinished")) {
                    JsonNode tsf      = root.get("testStepFinished");
                    String tcsId      = tsf.path("testCaseStartedId").asText();
                    String testStepId = tsf.path("testStepId").asText();
                    JsonNode result   = tsf.path("testStepResult");

                    // resolve name and keyword via testStepId -> pickleStepId -> pickle.steps
                    String pickleStepId = testStepIdToPickleStepId.get(testStepId);
                    String stepName     = pickleStepId != null
                            ? pickleStepIdToText.getOrDefault(pickleStepId, "") : "";
                    String keyword      = pickleStepId != null
                            ? pickleStepIdToKeyword.getOrDefault(pickleStepId, "") : "";

                    String status   = mapStatus(result.path("status").asText());
                    long durationMs = getDurationMs(result.path("duration"));
                    String errorMsg = result.path("message").asText(null);

                    Step step       = new Step();
                    step.testStepId = testStepId;
                    step.name       = stepName;
                    step.keyword    = keyword;
                    step.status     = status;
                    step.durationMs = durationMs;

                    if (errorMsg != null && !errorMsg.isBlank()) {
                        String[] parts    = errorMsg.split("\n", 2);
                        step.errorMessage = parts[0].trim();
                        step.stackTrace   = parts.length > 1 ? parts[1].trim() : null;
                    }

                    List<Step> steps = stepsMap.computeIfAbsent(tcsId, k -> new ArrayList<>());
                    step.order = steps.size() + 1;
                    steps.add(step);
                }

                // --- TestCaseFinished ---
                if (root.has("testCaseFinished")) {
                    JsonNode tcf = root.get("testCaseFinished");
                    String tcsId = tcf.path("testCaseStartedId").asText();

                    Scenario scenario = scenarioMap.get(tcsId);
                    List<Step> steps  = stepsMap.getOrDefault(tcsId, Collections.emptyList());

                    if (scenario != null) {
                        for (Step step : steps) {
                            StepExtra extra = stepExtras.get(step.testStepId);
                            if (extra != null) {
                                step.translatedText      = extra.translatedText;
                                step.screenshotUrl       = extra.screenshotUrl;
                                step.failedScreenshotUrl = extra.failedScreenshotUrl;
                            }
                        }

                        scenario.steps.addAll(steps);
                        scenario.computeStepAggregations();

                        String tcId     = testCaseStartedToCase.get(tcsId);
                        String pickleId = testCaseIdToPickleId.get(tcId);
                        String uri      = pickleIdToFeatureId.get(pickleId);
                        Feature feature = featureMap.get(uri);
                        if (feature != null) feature.scenarios.add(scenario);
                    }
                }
            }
        }

        TestRun run = new TestRun();
        run.runAt   = earliestTimestamp == Long.MAX_VALUE
                ? LocalDateTime.now()
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(earliestTimestamp), ZoneId.systemDefault());
        run.features.addAll(featureMap.values());
        run.computeAggregations();

        log.info("Parsed: {} features, {} scenarios, {} steps",
                run.totalFeatures, run.totalScenarios, run.totalSteps);
        log.info("Results: passed={}, failed={} (scenarios)",
                run.passedScenarios, run.failedScenarios);

        return run;
    }

    /**
     * Recursively walks gherkinDocument feature/scenarios/steps to extract
     * pickleStepId -> keyword mapping.
     * Gherkin steps have their own IDs which become pickleStepIds in pickle.steps.
     */
    private void extractKeywordsFromGherkin(JsonNode node, Map<String, String> keywordMap) {
        if (node.isMissingNode()) return;

        // scenario steps
        for (JsonNode child : node.path("children")) {
            JsonNode scenario = child.path("scenario");
            if (!scenario.isMissingNode()) {
                for (JsonNode step : scenario.path("steps")) {
                    String id      = step.path("id").asText();
                    String keyword = step.path("keyword").asText("").trim();
                    if (!id.isBlank()) keywordMap.put(id, keyword);
                }
            }
            // background steps
            JsonNode background = child.path("background");
            if (!background.isMissingNode()) {
                for (JsonNode step : background.path("steps")) {
                    String id      = step.path("id").asText();
                    String keyword = step.path("keyword").asText("").trim();
                    if (!id.isBlank()) keywordMap.put(id, keyword);
                }
            }
        }
    }

    private String extractFeatureName(String uri) {
        if (uri == null) return "Unknown";
        String name = uri.contains("/") ? uri.substring(uri.lastIndexOf('/') + 1) : uri;
        return name.replace(".feature", "").replace("_", " ");
    }

    private String mapStatus(String raw) {
        return switch (raw.toUpperCase()) {
            case "PASSED"  -> "passed";
            case "FAILED"  -> "failed";
            case "SKIPPED", "PENDING", "UNDEFINED", "AMBIGUOUS" -> "skipped";
            default -> "skipped";
        };
    }

    private long getDurationMs(JsonNode duration) {
        if (duration.isMissingNode()) return 0;
        long seconds     = duration.path("seconds").asLong(0);
        long nanos       = duration.path("nanos").asLong(0);
        return seconds * 1000 + nanos / 1_000_000;
    }

    private long getTimestampMs(JsonNode timestamp) {
        if (timestamp.isMissingNode()) return System.currentTimeMillis();
        long seconds = timestamp.path("seconds").asLong(0);
        long nanos   = timestamp.path("nanos").asLong(0);
        return seconds * 1000 + nanos / 1_000_000;
    }
}
