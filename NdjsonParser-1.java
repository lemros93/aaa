package sk.moja.db.importer.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sk.moja.db.importer.model.*;
import sk.moja.db.importer.plugin.StepExtra;
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
        Map<String, String>  pickleIdToName           = new HashMap<>(); // pickleId -> scenario name
        Map<String, String>  pickleIdToFeatureId      = new HashMap<>(); // pickleId -> uri
        Map<String, String>  pickleIdToGherkinId      = new HashMap<>(); // pickleId -> gherkin scenario id (from astNodeIds[0])
        Map<String, String>  pickleStepIdToText       = new HashMap<>(); // pickleStepId -> step text
        Map<String, String>  pickleStepIdToGherkinId  = new HashMap<>(); // pickleStepId -> gherkin step id (from astNodeIds[0])
        Map<String, String>  gherkinIdToKeyword        = new HashMap<>(); // gherkin node id -> keyword
        Map<String, Integer> gherkinIdToLine           = new HashMap<>(); // gherkin node id -> line (steps + scenarios)
        Map<String, String>  gherkinIdToTags           = new HashMap<>(); // gherkin node id -> comma-separated tags
        Map<String, String>  testStepIdToPickleStepId = new HashMap<>(); // testStepId -> pickleStepId
        Map<String, String>  testStepIdToHookType     = new HashMap<>(); // testStepId -> "before"/"after"
        Map<String, String>  testCaseIdToPickleId     = new HashMap<>(); // testCaseId -> pickleId
        Map<String, String>  testCaseStartedToCase    = new HashMap<>(); // testCaseStartedId -> testCaseId
        Map<String, Feature>  featureMap              = new LinkedHashMap<>(); // uri -> Feature
        Map<String, Scenario> scenarioMap             = new LinkedHashMap<>(); // testCaseStartedId -> Scenario
        Map<String, List<Step>> stepsMap              = new LinkedHashMap<>(); // testCaseStartedId -> steps

        long earliestTimestamp = Long.MAX_VALUE;
        long latestTimestamp   = Long.MIN_VALUE;

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

                // --- GherkinDocument (feature name + tags + keyword/line for steps/scenarios) ---
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
                        f.tags = extractTags(feature.path("tags"));
                    }
                    extractKeywordsFromGherkin(
                            doc.path("feature"),
                            gherkinIdToKeyword,
                            gherkinIdToLine,
                            gherkinIdToTags);
                }
                if (root.has("pickle")) {
                    JsonNode pickle = root.get("pickle");
                    String pickleId = pickle.path("id").asText();
                    pickleIdToName.put(pickleId, pickle.path("name").asText());
                    pickleIdToFeatureId.put(pickleId, pickle.path("uri").asText());

                    // astNodeIds[0] = gherkin scenario UUID -> used to look up line via gherkinIdToLine
                    JsonNode scenarioAstIds = pickle.path("astNodeIds");
                    if (scenarioAstIds.isArray() && scenarioAstIds.size() > 0) {
                        pickleIdToGherkinId.put(pickleId, scenarioAstIds.get(0).asText());
                    }

                    // pickle.steps[].astNodeIds[0] = gherkin step UUID -> keyword + line
                    for (JsonNode ps : pickle.path("steps")) {
                        String psId = ps.path("id").asText();
                        pickleStepIdToText.put(psId, ps.path("text").asText());

                        JsonNode stepAstIds = ps.path("astNodeIds");
                        if (stepAstIds.isArray() && stepAstIds.size() > 0) {
                            pickleStepIdToGherkinId.put(psId, stepAstIds.get(0).asText());
                        }
                    }
                }

                // --- TestCase — maps testStepId -> pickleStepId ---
                if (root.has("testCase")) {
                    JsonNode tc     = root.get("testCase");
                    String tcId     = tc.path("id").asText();
                    String pickleId = tc.path("pickleId").asText();
                    testCaseIdToPickleId.put(tcId, pickleId);

                    // Determine hook type by position:
                    // hooks before the first pickleStep = "before", hooks after = "after"
                    List<JsonNode> allSteps = new ArrayList<>();
                    tc.path("testSteps").forEach(allSteps::add);

                    int firstPickleIdx = -1;
                    int lastPickleIdx  = -1;
                    for (int i = 0; i < allSteps.size(); i++) {
                        if (!allSteps.get(i).path("pickleStepId").asText().isBlank()) {
                            if (firstPickleIdx == -1) firstPickleIdx = i;
                            lastPickleIdx = i;
                        }
                    }

                    for (int i = 0; i < allSteps.size(); i++) {
                        JsonNode ts         = allSteps.get(i);
                        String testStepId   = ts.path("id").asText();
                        String pickleStepId = ts.path("pickleStepId").asText();

                        if (!pickleStepId.isBlank()) {
                            // normal gherkin step
                            testStepIdToPickleStepId.put(testStepId, pickleStepId);
                        } else {
                            // hook step — classify by position relative to pickle steps
                            String hookType = (firstPickleIdx == -1 || i < firstPickleIdx)
                                    ? "before" : "after";
                            testStepIdToHookType.put(testStepId, hookType);
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
                    if (ts > 0 && ts < earliestTimestamp) earliestTimestamp = ts;

                    String pickleId   = testCaseIdToPickleId.get(tcId);
                    Scenario scenario = new Scenario();
                    scenario.name     = pickleId != null
                            ? pickleIdToName.getOrDefault(pickleId, "Unknown") : "Unknown";
                    scenario.startedAt = ts > 0 ? ts : 0;

                    // workerId — parallel runner sets this, serial runs leave it empty
                    String workerId = tcs.path("workerId").asText(null);
                    if (workerId == null || workerId.isBlank()) workerId = "thread-1";
                    scenario.threadId = workerId;

                    // scenario line: pickleId -> gherkin scenario UUID -> line
                    if (pickleId != null) {
                        String gherkinScenarioId = pickleIdToGherkinId.get(pickleId);
                        if (gherkinScenarioId != null) {
                            scenario.line = gherkinIdToLine.getOrDefault(gherkinScenarioId, 0);
                            scenario.tags = gherkinIdToTags.get(gherkinScenarioId);
                        }
                    }

                    scenarioMap.put(tcsId, scenario);
                    stepsMap.put(tcsId, new ArrayList<>());
                }

                // --- TestStepFinished ---
                if (root.has("testStepFinished")) {
                    JsonNode tsf      = root.get("testStepFinished");
                    String tcsId      = tsf.path("testCaseStartedId").asText();
                    String testStepId = tsf.path("testStepId").asText();
                    JsonNode result   = tsf.path("testStepResult");

                    String hookType = testStepIdToHookType.get(testStepId);
                    boolean isHook  = hookType != null;

                    if (isHook) {
                        if ("FAILED".equalsIgnoreCase(result.path("status").asText())) {
                            JsonNode exception = result.path("exception");
                            String errorMsg;
                            if (!exception.isMissingNode() && !exception.isNull()) {
                                // exception.message = krátky popis
                                errorMsg = exception.path("message").asText(null);
                            } else {
                                // Fallback — prvý riadok z message
                                String raw = result.path("message").asText(null);
                                errorMsg = raw != null ? raw.split("\n")[0].trim() : null;
                            }
                            Scenario scenario = scenarioMap.get(tcsId);
                            if (scenario != null && errorMsg != null && !errorMsg.isBlank()) {
                                scenario.hookErrorType    = hookType;
                                scenario.hookErrorMessage = errorMsg.trim();
                            }
                        }
                        // do not add hook to steps list — skip to next line
                    } else {
                        // normal pickle step — resolve name, keyword and line number
                        String pickleStepId = testStepIdToPickleStepId.get(testStepId);
                        String stepName     = pickleStepId != null
                                ? pickleStepIdToText.getOrDefault(pickleStepId, "") : "";

                        // keyword + line: pickleStepId -> gherkin step UUID -> keyword / line
                        String keyword = "";
                        int stepLine   = 0;
                        if (pickleStepId != null) {
                            String gherkinStepId = pickleStepIdToGherkinId.get(pickleStepId);
                            if (gherkinStepId != null) {
                                keyword  = gherkinIdToKeyword.getOrDefault(gherkinStepId, "");
                                stepLine = gherkinIdToLine.getOrDefault(gherkinStepId, 0);
                            }
                        }

                        String status     = mapStatus(result.path("status").asText());
                        long   durationMs = getDurationMs(result.path("duration"));

                        // Štruktúra NDJSON (Cucumber 7.x + Selenide):
                        // testStepResult.exception.type        = exception class name
                        // testStepResult.exception.message     = krátky popis chyby
                        // testStepResult.exception.stackTrace  = celý stack trace
                        // testStepResult.message               = rovnaký text ako exception.message (skrátený)
                        String errorMsg   = null;
                        String stackTrace = null;

                        JsonNode exception = result.path("exception");
                        if (!exception.isMissingNode() && !exception.isNull()) {
                            errorMsg   = exception.path("message").asText(null);
                            stackTrace = exception.path("stackTrace").asText(null);
                            // Ak stackTrace == message, splitneme stackTrace na časti
                            // (Selenide dáva celý text do stackTrace vrátane stack frames)
                            if (stackTrace != null && errorMsg != null && stackTrace.startsWith(errorMsg)) {
                                String rest = stackTrace.substring(errorMsg.length()).trim();
                                stackTrace = rest.isBlank() ? null : rest;
                            }
                        } else {
                            // Fallback pre prípad bez exception objektu
                            String raw = result.path("message").asText(null);
                            if (raw != null && !raw.isBlank()) {
                                String[] lines = raw.split("\n");
                                int stackStart = -1;
                                for (int i = 0; i < lines.length; i++) {
                                    String t = lines[i].trim();
                                    if (t.startsWith("at ") || t.startsWith("Caused by:")) {
                                        stackStart = i;
                                        break;
                                    }
                                }
                                if (stackStart > 0) {
                                    StringBuilder msg = new StringBuilder();
                                    for (int i = 0; i < stackStart; i++) {
                                        if (i > 0) msg.append("\n");
                                        msg.append(lines[i]);
                                    }
                                    StringBuilder st = new StringBuilder();
                                    for (int i = stackStart; i < lines.length; i++) {
                                        if (i > stackStart) st.append("\n");
                                        st.append(lines[i]);
                                    }
                                    errorMsg   = msg.toString().trim();
                                    stackTrace = st.toString().trim();
                                } else {
                                    errorMsg   = raw.trim();
                                    stackTrace = null;
                                }
                            }
                        }

                        Step step       = new Step();
                        step.testStepId = testStepId;
                        step.name       = stepName;
                        step.keyword    = keyword;
                        step.line       = stepLine;
                        step.status     = status;
                        step.durationMs = durationMs;
                        step.errorMessage = errorMsg   != null && !errorMsg.isBlank()   ? errorMsg.trim()   : null;
                        step.stackTrace   = stackTrace != null && !stackTrace.isBlank() ? stackTrace.trim() : null;

                        List<Step> steps = stepsMap.computeIfAbsent(tcsId, k -> new ArrayList<>());
                        steps.add(step);
                    }
                }

                // --- TestCaseFinished ---
                if (root.has("testCaseFinished")) {
                    JsonNode tcf = root.get("testCaseFinished");
                    String tcsId = tcf.path("testCaseStartedId").asText();

                    long ts = getTimestampMs(tcf.path("timestamp"));
                    if (ts > 0 && ts > latestTimestamp) latestTimestamp = ts;

                    Scenario scenario = scenarioMap.get(tcsId);
                    List<Step> steps  = stepsMap.getOrDefault(tcsId, Collections.emptyList());

                    if (scenario != null) {
                        // enrich steps — translatedText + screenshotUrl + failedScreenshotUrl
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
        run.computeAggregations(); // sets testsTotalMs = sum of all features

        // buildDurationMs = real build time (earliest to latest testCaseFinished)
        // useful for parallel runs where feature durations overlap
        if (earliestTimestamp != Long.MAX_VALUE && latestTimestamp != Long.MIN_VALUE) {
            run.buildDurationMs = latestTimestamp - earliestTimestamp;
        }

        log.info("Parsed: {} features, {} scenarios, {} steps",
                run.totalFeatures, run.totalScenarios, run.totalSteps);
        log.info("Results: passed={}, failed={} (scenarios)",
                run.passedScenarios, run.failedScenarios);

        return run;
    }

    private void extractKeywordsFromGherkin(
            JsonNode node,
            Map<String, String>  gherkinIdToKeyword,
            Map<String, Integer> gherkinIdToLine,
            Map<String, String>  gherkinIdToTags) {

        if (node.isMissingNode()) return;

        for (JsonNode child : node.path("children")) {
            // scenario
            JsonNode scenario = child.path("scenario");
            if (!scenario.isMissingNode()) {
                String scenarioId = scenario.path("id").asText();
                int scenarioLine  = scenario.path("location").path("line").asInt(0);
                if (!scenarioId.isBlank()) {
                    gherkinIdToLine.put(scenarioId, scenarioLine);
                    String tags = extractTags(scenario.path("tags"));
                    if (tags != null) gherkinIdToTags.put(scenarioId, tags);
                }

                for (JsonNode step : scenario.path("steps")) {
                    String id      = step.path("id").asText();
                    String keyword = step.path("keyword").asText("").trim();
                    int    line    = step.path("location").path("line").asInt(0);
                    if (!id.isBlank()) {
                        gherkinIdToKeyword.put(id, keyword);
                        gherkinIdToLine.put(id, line);
                    }
                }
            }
            // background
            JsonNode background = child.path("background");
            if (!background.isMissingNode()) {
                for (JsonNode step : background.path("steps")) {
                    String id      = step.path("id").asText();
                    String keyword = step.path("keyword").asText("").trim();
                    int    line    = step.path("location").path("line").asInt(0);
                    if (!id.isBlank()) {
                        gherkinIdToKeyword.put(id, keyword);
                        gherkinIdToLine.put(id, line);
                    }
                }
            }
        }
    }

    /** Extracts tags array into comma-separated string, e.g. "@smoke,@regression" */
    private String extractTags(JsonNode tagsNode) {
        if (tagsNode == null || tagsNode.isMissingNode() || !tagsNode.isArray() || tagsNode.size() == 0)
            return null;
        StringBuilder sb = new StringBuilder();
        for (JsonNode tag : tagsNode) {
            if (sb.length() > 0) sb.append(",");
            sb.append(tag.path("name").asText());
        }
        return sb.length() > 0 ? sb.toString() : null;
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
        if (timestamp == null || timestamp.isMissingNode()) return -1;
        long seconds = timestamp.path("seconds").asLong(0);
        long nanos   = timestamp.path("nanos").asLong(0);
        if (seconds == 0 && nanos == 0) return -1;
        return seconds * 1000 + nanos / 1_000_000;
    }
}
