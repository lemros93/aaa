# Cucumber Importer — Kompletný kód

## Štruktúra projektu

```
cucumber-importer/
├── pom.xml
└── src/main/
    ├── resources/
    │   └── init.sql
    └── java/com/yourcompany/importer/
        ├── Main.java
        ├── config/
        │   └── ImportConfig.java
        ├── model/
        │   ├── TestRun.java
        │   ├── Feature.java
        │   ├── Scenario.java
        │   └── Step.java
        ├── plugin/
        │   ├── StepExtra.java
        │   └── StepEnrichmentPlugin.java
        ├── parser/
        │   ├── NdjsonParser.java
        │   └── StepExtraParser.java
        └── db/
            └── DatabaseWriter.java
```

---

## pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.yourcompany</groupId>
    <artifactId>cucumber-importer</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <version>8.3.0</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>2.17.0</version>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-simple</artifactId>
            <version>2.0.12</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-assembly-plugin</artifactId>
                <version>3.6.0</version>
                <configuration>
                    <archive>
                        <manifest>
                            <mainClass>com.yourcompany.importer.Main</mainClass>
                        </manifest>
                    </archive>
                    <descriptorRefs>
                        <descriptorRef>jar-with-dependencies</descriptorRef>
                    </descriptorRefs>
                    <finalName>cucumber-importer</finalName>
                    <appendAssemblyId>false</appendAssemblyId>
                </configuration>
                <executions>
                    <execution>
                        <id>make-assembly</id>
                        <phase>package</phase>
                        <goals><goal>single</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## init.sql

```sql
-- Cucumber Test Results Database
-- Web project schema

CREATE TABLE IF NOT EXISTS runs (
    id                  INT AUTO_INCREMENT PRIMARY KEY,
    project             VARCHAR(100)    NOT NULL,
    project_type        VARCHAR(10)     NOT NULL DEFAULT 'web',
    run_at              DATETIME        NOT NULL,
    duration_ms         BIGINT,
    triggered_by        VARCHAR(100),
    trigger_type        VARCHAR(20),
    branch              VARCHAR(100),
    environment         VARCHAR(50),
    build_number        VARCHAR(50),
    total_features      INT             DEFAULT 0,
    passed_features     INT             DEFAULT 0,
    failed_features     INT             DEFAULT 0,
    total_scenarios     INT             DEFAULT 0,
    passed_scenarios    INT             DEFAULT 0,
    failed_scenarios    INT             DEFAULT 0,
    total_steps         INT             DEFAULT 0,
    passed_steps        INT             DEFAULT 0,
    failed_steps        INT             DEFAULT 0,
    skipped_steps       INT             DEFAULT 0,
    created_at          DATETIME        DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS run_metadata (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    run_id      INT             NOT NULL,
    meta_key    VARCHAR(100)    NOT NULL,
    meta_value  VARCHAR(500),
    FOREIGN KEY (run_id) REFERENCES runs(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS features (
    id                  INT AUTO_INCREMENT PRIMARY KEY,
    run_id              INT             NOT NULL,
    name                VARCHAR(255)    NOT NULL,
    file_path           VARCHAR(500),
    status              VARCHAR(10)     NOT NULL,
    duration_ms         BIGINT,
    total_scenarios     INT             DEFAULT 0,
    passed_scenarios    INT             DEFAULT 0,
    failed_scenarios    INT             DEFAULT 0,
    FOREIGN KEY (run_id) REFERENCES runs(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS scenarios (
    id              INT AUTO_INCREMENT PRIMARY KEY,
    feature_id      INT             NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    status          VARCHAR(10)     NOT NULL,
    duration_ms     BIGINT,
    total_steps     INT             DEFAULT 0,
    passed_steps    INT             DEFAULT 0,
    failed_steps    INT             DEFAULT 0,
    skipped_steps   INT             DEFAULT 0,
    FOREIGN KEY (feature_id) REFERENCES features(id) ON DELETE CASCADE
);

-- stepy len pre failed scenáre
CREATE TABLE IF NOT EXISTS steps (
    id                    INT AUTO_INCREMENT PRIMARY KEY,
    scenario_id           INT             NOT NULL,
    step_order            INT             NOT NULL,
    keyword               VARCHAR(20),
    name                  VARCHAR(500)    NOT NULL,
    translated_text       VARCHAR(500),
    status                VARCHAR(10)     NOT NULL,
    duration_ms           BIGINT,
    error_message         TEXT,
    stack_trace           TEXT,
    screenshot_url        VARCHAR(1000),
    failed_screenshot_url VARCHAR(1000),
    FOREIGN KEY (scenario_id) REFERENCES scenarios(id) ON DELETE CASCADE
);

CREATE INDEX idx_runs_project         ON runs(project);
CREATE INDEX idx_runs_run_at          ON runs(run_at);
CREATE INDEX idx_features_run_id      ON features(run_id);
CREATE INDEX idx_features_status      ON features(status);
CREATE INDEX idx_scenarios_feature_id ON scenarios(feature_id);
CREATE INDEX idx_scenarios_status     ON scenarios(status);
CREATE INDEX idx_steps_scenario_id    ON steps(scenario_id);
```

---

## Main.java

```java
package com.yourcompany.importer;

import com.yourcompany.importer.config.ImportConfig;
import com.yourcompany.importer.db.DatabaseWriter;
import com.yourcompany.importer.model.TestRun;
import com.yourcompany.importer.parser.NdjsonParser;
import com.yourcompany.importer.parser.StepExtraParser;
import com.yourcompany.importer.plugin.StepExtra;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("=== Cucumber NDJSON Importer ===");

        ImportConfig config;
        try {
            config = ImportConfig.fromSystemProperties();
        } catch (IllegalArgumentException e) {
            log.error("Configuration error: {}", e.getMessage());
            log.error("Required properties: -Dndjson=... -Ddb.url=... -Ddb.user=... -Ddb.password=...");
            System.exit(1);
            return;
        }

        log.info("Project:      {}", config.project);
        log.info("Environment:  {}", config.environment);
        log.info("Branch:       {}", config.branch);
        log.info("Build:        {}", config.buildNumber);
        log.info("Triggered by: {} ({})", config.triggeredBy, config.triggerType);
        log.info("Browser:      {} {}", config.browser, config.browserVersion);
        log.info("NDJSON file:  {}", config.ndjsonPath);

        NdjsonParser    parser      = new NdjsonParser();
        StepExtraParser extraParser = new StepExtraParser();
        DatabaseWriter  writer      = null;

        try {
            Map<String, StepExtra> stepExtras = extraParser.parse(config.stepsExtraPath);
            TestRun run = parser.parse(config.ndjsonPath, stepExtras);
            writer      = new DatabaseWriter(config);
            writer.save(run, config);
            log.info("=== Import completed successfully ===");

        } catch (Exception e) {
            log.error("Import failed: {}", e.getMessage(), e);
            System.exit(2);

        } finally {
            if (writer != null) writer.close();
        }
    }
}
```

---

## config/ImportConfig.java

```java
package com.yourcompany.importer.config;

public class ImportConfig {

    public final String ndjsonPath;
    public final String stepsExtraPath;
    public final String dbUrl;
    public final String dbUser;
    public final String dbPassword;
    public final String project;
    public final String environment;
    public final String branch;
    public final String buildNumber;
    public final String triggeredBy;
    public final String triggerType;
    public final String browser;
    public final String browserVersion;

    private ImportConfig(Builder b) {
        this.ndjsonPath      = b.ndjsonPath;
        this.stepsExtraPath  = b.stepsExtraPath;
        this.dbUrl           = b.dbUrl;
        this.dbUser          = b.dbUser;
        this.dbPassword      = b.dbPassword;
        this.project         = b.project;
        this.environment     = b.environment;
        this.branch          = b.branch;
        this.buildNumber     = b.buildNumber;
        this.triggeredBy     = b.triggeredBy;
        this.triggerType     = b.triggerType;
        this.browser         = b.browser;
        this.browserVersion  = b.browserVersion;
    }

    public static ImportConfig fromSystemProperties() {
        return new Builder()
            .ndjsonPath    (require("ndjson"))
            .stepsExtraPath(prop("steps.extra", "target/steps-extra.ndjson"))
            .dbUrl         (require("db.url"))
            .dbUser        (require("db.user"))
            .dbPassword    (require("db.password"))
            .project       (prop("project",         "web-project"))
            .environment   (prop("env",             "unknown"))
            .branch        (prop("branch",          "unknown"))
            .buildNumber   (prop("build.number",    "unknown"))
            .triggeredBy   (prop("triggered.by",    "unknown"))
            .triggerType   (prop("trigger.type",    "manual"))
            .browser       (prop("browser",         "unknown"))
            .browserVersion(prop("browser.version", "unknown"))
            .build();
    }

    private static String require(String key) {
        String val = System.getProperty(key);
        if (val == null || val.isBlank())
            throw new IllegalArgumentException("Required system property missing: -D" + key);
        return val;
    }

    private static String prop(String key, String defaultValue) {
        String val = System.getProperty(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    public static class Builder {
        private String ndjsonPath, stepsExtraPath, dbUrl, dbUser, dbPassword;
        private String project, environment, branch, buildNumber;
        private String triggeredBy, triggerType;
        private String browser, browserVersion;

        public Builder ndjsonPath    (String v) { this.ndjsonPath     = v; return this; }
        public Builder stepsExtraPath(String v) { this.stepsExtraPath = v; return this; }
        public Builder dbUrl         (String v) { this.dbUrl          = v; return this; }
        public Builder dbUser        (String v) { this.dbUser         = v; return this; }
        public Builder dbPassword    (String v) { this.dbPassword     = v; return this; }
        public Builder project       (String v) { this.project        = v; return this; }
        public Builder environment   (String v) { this.environment    = v; return this; }
        public Builder branch        (String v) { this.branch         = v; return this; }
        public Builder buildNumber   (String v) { this.buildNumber    = v; return this; }
        public Builder triggeredBy   (String v) { this.triggeredBy    = v; return this; }
        public Builder triggerType   (String v) { this.triggerType    = v; return this; }
        public Builder browser       (String v) { this.browser        = v; return this; }
        public Builder browserVersion(String v) { this.browserVersion = v; return this; }
        public ImportConfig build()             { return new ImportConfig(this); }
    }
}
```

---

## model/TestRun.java

```java
package com.yourcompany.importer.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class TestRun {
    public LocalDateTime runAt;
    public long durationMs;
    public List<Feature> features = new ArrayList<>();

    public int totalFeatures, passedFeatures, failedFeatures;
    public int totalScenarios, passedScenarios, failedScenarios;
    public int totalSteps, passedSteps, failedSteps, skippedSteps;

    public void computeAggregations() {
        totalFeatures   = features.size();
        passedFeatures  = 0;
        failedFeatures  = 0;
        totalScenarios  = 0;
        passedScenarios = 0;
        failedScenarios = 0;
        totalSteps      = 0;
        passedSteps     = 0;
        failedSteps     = 0;
        skippedSteps    = 0;

        for (Feature f : features) {
            f.computeAggregations();
            if ("passed".equals(f.status)) passedFeatures++;
            else failedFeatures++;
            totalScenarios  += f.totalScenarios;
            passedScenarios += f.passedScenarios;
            failedScenarios += f.failedScenarios;
            totalSteps      += f.totalSteps;
            passedSteps     += f.passedSteps;
            failedSteps     += f.failedSteps;
            skippedSteps    += f.skippedSteps;
        }
        durationMs = features.stream().mapToLong(f -> f.durationMs).sum();
    }
}
```

---

## model/Feature.java

```java
package com.yourcompany.importer.model;

import java.util.ArrayList;
import java.util.List;

public class Feature {
    public String name;
    public String filePath;
    public String status;
    public long durationMs;
    public List<Scenario> scenarios = new ArrayList<>();

    public int totalScenarios, passedScenarios, failedScenarios;
    public int totalSteps, passedSteps, failedSteps, skippedSteps;

    public void computeAggregations() {
        totalScenarios  = scenarios.size();
        passedScenarios = 0;
        failedScenarios = 0;
        totalSteps      = 0;
        passedSteps     = 0;
        failedSteps     = 0;
        skippedSteps    = 0;
        boolean anyFailed = false;

        for (Scenario s : scenarios) {
            if ("passed".equals(s.status)) passedScenarios++;
            else { failedScenarios++; anyFailed = true; }
            totalSteps   += s.totalSteps;
            passedSteps  += s.passedSteps;
            failedSteps  += s.failedSteps;
            skippedSteps += s.skippedSteps;
            durationMs   += s.durationMs;
        }
        status = anyFailed ? "failed" : "passed";
    }
}
```

---

## model/Scenario.java

```java
package com.yourcompany.importer.model;

import java.util.ArrayList;
import java.util.List;

public class Scenario {
    public String name;
    public String status;
    public long durationMs;
    public List<Step> steps = new ArrayList<>();

    public int totalSteps, passedSteps, failedSteps, skippedSteps;

    public void computeStepAggregations() {
        totalSteps   = steps.size();
        passedSteps  = 0;
        failedSteps  = 0;
        skippedSteps = 0;
        boolean anyFailed = false;

        for (Step s : steps) {
            durationMs += s.durationMs;
            switch (s.status) {
                case "passed"  -> passedSteps++;
                case "failed"  -> { failedSteps++; anyFailed = true; }
                case "skipped" -> skippedSteps++;
            }
        }
        status = anyFailed ? "failed" : (skippedSteps > 0 && passedSteps == 0) ? "skipped" : "passed";
    }

    public boolean isFailed() {
        return "failed".equals(status);
    }
}
```

---

## model/Step.java

```java
package com.yourcompany.importer.model;

public class Step {
    public int order;
    public String keyword;
    public String name;               // originálny text z .feature súboru
    public String translatedText;     // preložené hodnoty z property súborov
    public String status;
    public long durationMs;
    public String errorMessage;
    public String stackTrace;
    public String screenshotUrl;      // screenshot po každom stepe (voliteľné)
    public String failedScreenshotUrl;// screenshot pri faili (voliteľné)
}
```

---

## plugin/StepExtra.java

```java
package com.yourcompany.importer.plugin;

public class StepExtra {
    public String testCaseStartedId;
    public int    stepOrder;
    public String translatedText;
    public String screenshotUrl;
    public String failedScreenshotUrl;
}
```

---

## plugin/StepEnrichmentPlugin.java

```java
package com.yourcompany.importer.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.importer.context.StepContext;
import io.cucumber.plugin.EventListener;
import io.cucumber.plugin.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registrácia v @CucumberOptions:
 *   plugin = {"com.yourcompany.importer.plugin.StepEnrichmentPlugin:target/steps-extra.ndjson"}
 */
public class StepEnrichmentPlugin implements EventListener {

    private static final Logger log = LoggerFactory.getLogger(StepEnrichmentPlugin.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path outputPath;

    // ConcurrentHashMap — bezpečné pre paralelné behy
    private final Map<String, Integer> stepCounters = new ConcurrentHashMap<>();

    // ThreadLocal — každé vlákno má vlastné testCaseStartedId
    private final ThreadLocal<String> currentTestCaseStartedId = new ThreadLocal<>();

    public StepEnrichmentPlugin(String outputPath) {
        this.outputPath = Paths.get(outputPath);
        initOutputFile();
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestCaseStarted.class,  this::onTestCaseStarted);
        publisher.registerHandlerFor(TestStepFinished.class, this::onTestStepFinished);
    }

    private void onTestCaseStarted(TestCaseStarted event) {
        String tcsId = event.getTestCase().getId().toString();
        currentTestCaseStartedId.set(tcsId);
        stepCounters.put(tcsId, 0);
    }

    private void onTestStepFinished(TestStepFinished event) {
        if (!(event.getTestStep() instanceof PickleStepTestStep)) return;

        String tcsId = currentTestCaseStartedId.get();
        if (tcsId == null) {
            log.warn("StepEnrichmentPlugin: testCaseStartedId is null, skipping step");
            return;
        }

        int stepOrder = stepCounters.merge(tcsId, 1, Integer::sum);

        StepExtra extra = new StepExtra();
        extra.testCaseStartedId   = tcsId;
        extra.stepOrder           = stepOrder;
        extra.translatedText      = StepContext.getCurrentStepName();
        extra.screenshotUrl       = nullIfBlank(StepContext.getScreenshotUrl());
        extra.failedScreenshotUrl = nullIfBlank(StepContext.getFailedScreenshotUrl());

        writeLine(extra);
    }

    private void initOutputFile() {
        try {
            Path parent = outputPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(outputPath, new byte[0],
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.error("StepEnrichmentPlugin: cannot create output file: {}", outputPath, e);
        }
    }

    private synchronized void writeLine(StepExtra extra) {
        try (BufferedWriter writer = Files.newBufferedWriter(
                outputPath, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            writer.write(mapper.writeValueAsString(extra));
            writer.newLine();
        } catch (IOException e) {
            log.error("StepEnrichmentPlugin: failed to write step extra tcsId={} order={}",
                    extra.testCaseStartedId, extra.stepOrder, e);
        }
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
```

---

## parser/StepExtraParser.java

```java
package com.yourcompany.importer.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourcompany.importer.plugin.StepExtra;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class StepExtraParser {

    private static final Logger log = LoggerFactory.getLogger(StepExtraParser.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, StepExtra> parse(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            log.warn("steps-extra.ndjson not found at {}, skipping enrichment", filePath);
            return Collections.emptyMap();
        }

        Map<String, StepExtra> result = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                StepExtra extra = mapper.readValue(line, StepExtra.class);
                result.put(key(extra.testCaseStartedId, extra.stepOrder), extra);
            }
        }

        log.info("Loaded {} step enrichment records from {}", result.size(), filePath);
        return result;
    }

    public static String key(String testCaseStartedId, int stepOrder) {
        return testCaseStartedId + ":" + stepOrder;
    }
}
```

---

## parser/NdjsonParser.java

```java
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

        Map<String, String>   pickleIdToName        = new HashMap<>();
        Map<String, String>   pickleIdToFeatureId   = new HashMap<>();
        Map<String, String>   testCaseIdToPickleId  = new HashMap<>();
        Map<String, String>   testCaseStartedToCase = new HashMap<>();
        Map<String, Feature>  featureMap            = new LinkedHashMap<>();
        Map<String, Scenario> scenarioMap           = new LinkedHashMap<>();
        Map<String, List<Step>> stepsMap            = new LinkedHashMap<>();

        long earliestTimestamp = Long.MAX_VALUE;

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                JsonNode root = mapper.readTree(line);

                if (root.has("source")) {
                    JsonNode source = root.get("source");
                    String uri = source.path("uri").asText();
                    Feature f  = new Feature();
                    f.filePath = uri;
                    f.name     = extractFeatureName(uri);
                    featureMap.put(uri, f);
                }

                if (root.has("gherkinDocument")) {
                    JsonNode doc     = root.get("gherkinDocument");
                    String uri       = doc.path("uri").asText();
                    JsonNode feature = doc.path("feature");
                    if (!feature.isMissingNode()) {
                        Feature f = featureMap.computeIfAbsent(uri, u -> {
                            Feature nf = new Feature(); nf.filePath = u; return nf;
                        });
                        f.name = feature.path("name").asText(f.name);
                    }
                }

                if (root.has("pickle")) {
                    JsonNode pickle = root.get("pickle");
                    String pickleId = pickle.path("id").asText();
                    pickleIdToName.put(pickleId, pickle.path("name").asText());
                    pickleIdToFeatureId.put(pickleId, pickle.path("uri").asText());
                }

                if (root.has("testCase")) {
                    JsonNode tc = root.get("testCase");
                    testCaseIdToPickleId.put(tc.path("id").asText(), tc.path("pickleId").asText());
                }

                if (root.has("testCaseStarted")) {
                    JsonNode tcs = root.get("testCaseStarted");
                    String tcsId = tcs.path("id").asText();
                    String tcId  = tcs.path("testCaseId").asText();
                    testCaseStartedToCase.put(tcsId, tcId);

                    long ts = getTimestampMs(tcs.path("timestamp"));
                    if (ts < earliestTimestamp) earliestTimestamp = ts;

                    String pickleId = testCaseIdToPickleId.get(tcId);
                    Scenario scenario = new Scenario();
                    scenario.name = pickleId != null
                            ? pickleIdToName.getOrDefault(pickleId, "Unknown") : "Unknown";
                    scenarioMap.put(tcsId, scenario);
                    stepsMap.put(tcsId, new ArrayList<>());
                }

                if (root.has("testStepFinished")) {
                    JsonNode tsf    = root.get("testStepFinished");
                    String tcsId    = tsf.path("testCaseStartedId").asText();
                    JsonNode result = tsf.path("testStepResult");

                    Step step       = new Step();
                    step.status     = mapStatus(result.path("status").asText());
                    step.durationMs = getDurationMs(result.path("duration"));

                    String errorMsg = result.path("message").asText(null);
                    if (errorMsg != null && !errorMsg.isBlank()) {
                        String[] parts    = errorMsg.split("\n", 2);
                        step.errorMessage = parts[0].trim();
                        step.stackTrace   = parts.length > 1 ? parts[1].trim() : null;
                    }

                    List<Step> steps = stepsMap.computeIfAbsent(tcsId, k -> new ArrayList<>());
                    step.order = steps.size() + 1;
                    steps.add(step);
                }

                if (root.has("testCaseFinished")) {
                    JsonNode tcf = root.get("testCaseFinished");
                    String tcsId = tcf.path("testCaseStartedId").asText();

                    Scenario scenario = scenarioMap.get(tcsId);
                    List<Step> steps  = stepsMap.getOrDefault(tcsId, Collections.emptyList());

                    if (scenario != null) {
                        for (Step step : steps) {
                            String key    = StepExtraParser.key(tcsId, step.order);
                            StepExtra extra = stepExtras.get(key);
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

    private String extractFeatureName(String uri) {
        if (uri == null) return "Unknown";
        String name = uri.contains("/") ? uri.substring(uri.lastIndexOf('/') + 1) : uri;
        return name.replace(".feature", "").replace("_", " ");
    }

    private String mapStatus(String raw) {
        return switch (raw.toUpperCase()) {
            case "PASSED"  -> "passed";
            case "FAILED"  -> "failed";
            default        -> "skipped";
        };
    }

    private long getDurationMs(JsonNode duration) {
        if (duration.isMissingNode()) return 0;
        return duration.path("seconds").asLong(0) * 1000
             + duration.path("nanos").asLong(0) / 1_000_000;
    }

    private long getTimestampMs(JsonNode timestamp) {
        if (timestamp.isMissingNode()) return System.currentTimeMillis();
        return timestamp.path("seconds").asLong(0) * 1000
             + timestamp.path("nanos").asLong(0) / 1_000_000;
    }
}
```

---

## db/DatabaseWriter.java

```java
package com.yourcompany.importer.db;

import com.yourcompany.importer.config.ImportConfig;
import com.yourcompany.importer.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.Map;

public class DatabaseWriter {

    private static final Logger log = LoggerFactory.getLogger(DatabaseWriter.class);
    private final Connection conn;

    public DatabaseWriter(ImportConfig config) throws SQLException {
        log.info("Connecting to database: {}", config.dbUrl);
        this.conn = DriverManager.getConnection(config.dbUrl, config.dbUser, config.dbPassword);
        this.conn.setAutoCommit(false);
        log.info("Database connection established");
    }

    public void save(TestRun run, ImportConfig config) throws SQLException {
        try {
            long runId = insertRun(run, config);
            insertRunMetadata(runId, config);

            for (Feature feature : run.features) {
                long featureId = insertFeature(runId, feature);
                for (Scenario scenario : feature.scenarios) {
                    long scenarioId = insertScenario(featureId, scenario);
                    if (scenario.isFailed()) {
                        insertSteps(scenarioId, scenario);
                    }
                }
            }

            conn.commit();
            log.info("Successfully saved run ID={} to database", runId);

        } catch (SQLException e) {
            conn.rollback();
            log.error("Database error, transaction rolled back", e);
            throw e;
        }
    }

    private long insertRun(TestRun run, ImportConfig config) throws SQLException {
        String sql = """
            INSERT INTO runs (
                project, project_type, run_at, duration_ms,
                triggered_by, trigger_type, branch, environment, build_number,
                total_features, passed_features, failed_features,
                total_scenarios, passed_scenarios, failed_scenarios,
                total_steps, passed_steps, failed_steps, skipped_steps
            ) VALUES (?, 'web', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, config.project);
            ps.setTimestamp(2, Timestamp.valueOf(run.runAt));
            ps.setLong(3, run.durationMs);
            ps.setString(4, config.triggeredBy);
            ps.setString(5, config.triggerType);
            ps.setString(6, config.branch);
            ps.setString(7, config.environment);
            ps.setString(8, config.buildNumber);
            ps.setInt(9,  run.totalFeatures);
            ps.setInt(10, run.passedFeatures);
            ps.setInt(11, run.failedFeatures);
            ps.setInt(12, run.totalScenarios);
            ps.setInt(13, run.passedScenarios);
            ps.setInt(14, run.failedScenarios);
            ps.setInt(15, run.totalSteps);
            ps.setInt(16, run.passedSteps);
            ps.setInt(17, run.failedSteps);
            ps.setInt(18, run.skippedSteps);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            return keys.getLong(1);
        }
    }

    private void insertRunMetadata(long runId, ImportConfig config) throws SQLException {
        Map<String, String> metadata = Map.of(
            "browser",         config.browser,
            "browser_version", config.browserVersion
        );
        String sql = "INSERT INTO run_metadata (run_id, meta_key, meta_value) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                ps.setLong(1, runId);
                ps.setString(2, entry.getKey());
                ps.setString(3, entry.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private long insertFeature(long runId, Feature feature) throws SQLException {
        String sql = """
            INSERT INTO features (
                run_id, name, file_path, status, duration_ms,
                total_scenarios, passed_scenarios, failed_scenarios
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, runId);
            ps.setString(2, feature.name);
            ps.setString(3, feature.filePath);
            ps.setString(4, feature.status);
            ps.setLong(5, feature.durationMs);
            ps.setInt(6, feature.totalScenarios);
            ps.setInt(7, feature.passedScenarios);
            ps.setInt(8, feature.failedScenarios);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            return keys.getLong(1);
        }
    }

    private long insertScenario(long featureId, Scenario scenario) throws SQLException {
        String sql = """
            INSERT INTO scenarios (
                feature_id, name, status, duration_ms,
                total_steps, passed_steps, failed_steps, skipped_steps
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, featureId);
            ps.setString(2, scenario.name);
            ps.setString(3, scenario.status);
            ps.setLong(4, scenario.durationMs);
            ps.setInt(5, scenario.totalSteps);
            ps.setInt(6, scenario.passedSteps);
            ps.setInt(7, scenario.failedSteps);
            ps.setInt(8, scenario.skippedSteps);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            return keys.getLong(1);
        }
    }

    private void insertSteps(long scenarioId, Scenario scenario) throws SQLException {
        String sql = """
            INSERT INTO steps (
                scenario_id, step_order, keyword, name, translated_text,
                status, duration_ms, error_message, stack_trace,
                screenshot_url, failed_screenshot_url
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Step step : scenario.steps) {
                ps.setLong(1, scenarioId);
                ps.setInt(2, step.order);
                ps.setString(3, step.keyword);
                ps.setString(4, step.name);
                ps.setString(5, step.translatedText);
                ps.setString(6, step.status);
                ps.setLong(7, step.durationMs);
                ps.setString(8, step.errorMessage);
                ps.setString(9, step.stackTrace);
                ps.setString(10, step.screenshotUrl);
                ps.setString(11, step.failedScreenshotUrl);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public void close() {
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException e) {
            log.warn("Error closing connection", e);
        }
    }
}
```

---

## Použitie

### 1. Init DB (raz)
```bash
mysql -u user -p databaza < init.sql
```

### 2. Registrácia pluginu v teste
```java
@CucumberOptions(
    plugin = {
        "message:target/cucumber.ndjson",
        "com.yourcompany.importer.plugin.StepEnrichmentPlugin:target/steps-extra.ndjson",
        "pretty"
    }
)
```

### 3. Build
```bash
mvn clean package
```

### 4. Bamboo task
```bash
java -jar cucumber-importer.jar \
  -Dndjson=target/cucumber.ndjson \
  -Ddb.url=jdbc:mysql://localhost:3306/tvoja_db \
  -Ddb.user=user \
  -Ddb.password=pass \
  -Dproject=web-project \
  -Denv=staging \
  -Dbranch=${bamboo.repository.branch.name} \
  -Dbuild.number=${bamboo.buildNumber} \
  -Dtrigger.type=scheduled \
  -Dtriggered.by=${bamboo.ManualBuildTriggerReason.userName} \
  -Dbrowser=chrome \
  -Dbrowser.version=124
```

### Nezabudni zmeniť
- `com.yourcompany` → váš skutočný package name
- `com.yourcompany.importer.context.StepContext` → skutočná cesta k tvojej triede
