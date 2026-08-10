package sk.moja.db.importer.config;

import static sk.moja.enumerators.Constants.DESTINATION;
import static sk.moja.enumerators.Constants.LOCAL_DESTINATION;

public class ImportConfig {

    // required
    public final String ndjsonPath;
    public final String stepsExtraPath;
    public final String dbUrl;
    public final String dbUser;
    public final String dbPassword;

    // run info
    public final String project;
    public final String environment;
    public final String branch;
    public final String commitHash;
    public final String buildNumber;
    public final String triggeredBy;
    public final String triggerType;
    public final String parallelMode;
    public final int    threadCount;
    public final boolean jiraReporting;     // true = ostra reportovanie do Jiry (jiraDryRun=false)
    public final String  jiraCycleIds;      // comma-separated Jira Test Cycle Keys
    public final boolean jiraNewExecution;   // true = vzdy vytvorit novu exekuciu (nie reusovat existujucu)
    public final String  cucumberTags;       // bamboo_tags: Cucumber tagy ktore sa pustia
    public final String  testedFeature;      // bamboo_tested_feature: feature/y ktore sa testuju
    public final String  testProperties;     // bamboo_test_properties: property file ktory sa nacita

    // web specific
    public final String browser;
    public final String browserVersion;
    public final String appUrl;

    private ImportConfig(Builder b) {
        this.ndjsonPath     = b.ndjsonPath;
        this.stepsExtraPath = b.stepsExtraPath;
        this.dbUrl          = b.dbUrl;
        this.dbUser         = b.dbUser;
        this.dbPassword     = b.dbPassword;
        this.project        = b.project;
        this.environment    = b.environment;
        this.branch         = b.branch;
        this.commitHash     = b.commitHash;
        this.buildNumber    = b.buildNumber;
        this.triggeredBy    = b.triggeredBy;
        this.triggerType    = b.triggerType;
        this.parallelMode   = b.parallelMode;
        this.threadCount    = b.threadCount;
        this.jiraReporting   = b.jiraReporting;
        this.jiraCycleIds    = b.jiraCycleIds;
        this.jiraNewExecution = b.jiraNewExecution;
        this.cucumberTags     = b.cucumberTags;
        this.testedFeature    = b.testedFeature;
        this.testProperties   = b.testProperties;
        this.browser        = b.browser;
        this.browserVersion = b.browserVersion;
        this.appUrl         = b.appUrl;
    }

    public static ImportConfig fromSystemProperties() {
        return new Builder()
            .ndjsonPath    (prop("ndjson",          LOCAL_DESTINATION + DESTINATION + "/temp/reports/cucumber-report/cucumber.ndjson"))
            .stepsExtraPath(prop("steps.extra",     LOCAL_DESTINATION + DESTINATION + "/temp/reports/cucumber-report/cucumberextra.ndjson"))
            .dbUrl         (require("db.url"))
            .dbUser        (require("db.user"))
            .dbPassword    (require("db.password"))
            .project       (prop("project",         "web-project"))
            .environment   (prop("env",             "unknown"))
            .branch        (prop("branch",          "unknown"))
            .commitHash    (prop("commit.hash",     "unknown"))
            .buildNumber   (prop("build.number",    "unknown"))
            .triggeredBy   (prop("triggered.by",    "unknown"))
            .triggerType   (prop("trigger.type",    "manual"))
            .parallelMode  (prop("parallel.mode",   "serial"))
            .threadCount   (Integer.parseInt(prop("thread.count",    "1")))
            .jiraReporting  (Boolean.parseBoolean(prop("jiraDryRun",          "true")) == false)
            .jiraCycleIds   (prop("jiraTestCycleKey", ""))
            .jiraNewExecution(Boolean.parseBoolean(prop("jiraNewExecution",      "false")))
            .cucumberTags    (prop("bamboo_tags",            ""))
            .testedFeature   (prop("bamboo_tested_feature",  ""))
            .testProperties  (prop("bamboo_test_properties", ""))
            .browser       (prop("browser",         "unknown"))
            .browserVersion(prop("browser.version", "unknown"))
            .appUrl        (prop("app.url",         ""))
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
        private String  ndjsonPath, stepsExtraPath, dbUrl, dbUser, dbPassword;
        private String  project, environment, branch, commitHash, buildNumber;
        private String  triggeredBy, triggerType, parallelMode;
        private int     threadCount    = 1;
        private boolean jiraReporting    = false;
        private String  jiraCycleIds     = "";
        private boolean jiraNewExecution = false;
        private String  cucumberTags     = "";
        private String  testedFeature    = "";
        private String  testProperties   = "";
        private String  browser, browserVersion, appUrl;

        public Builder ndjsonPath(String ndjsonPath) {
            this.ndjsonPath = ndjsonPath;
            return this;
        }

        public Builder stepsExtraPath(String stepsExtraPath) {
            this.stepsExtraPath = stepsExtraPath;
            return this;
        }

        public Builder dbUrl(String dbUrl) {
            this.dbUrl = dbUrl;
            return this;
        }

        public Builder dbUser(String dbUser) {
            this.dbUser = dbUser;
            return this;
        }

        public Builder dbPassword(String dbPassword) {
            this.dbPassword = dbPassword;
            return this;
        }

        public Builder project(String project) {
            this.project = project;
            return this;
        }

        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        public Builder branch(String branch) {
            this.branch = branch;
            return this;
        }

        public Builder commitHash(String commitHash) {
            this.commitHash = commitHash;
            return this;
        }

        public Builder buildNumber(String buildNumber) {
            this.buildNumber = buildNumber;
            return this;
        }

        public Builder triggeredBy(String triggeredBy) {
            this.triggeredBy = triggeredBy;
            return this;
        }

        public Builder triggerType(String triggerType) {
            this.triggerType = triggerType;
            return this;
        }

        public Builder parallelMode(String parallelMode) {
            this.parallelMode = parallelMode;
            return this;
        }

        public Builder threadCount(int threadCount) {
            this.threadCount = threadCount;
            return this;
        }

        public Builder jiraReporting(boolean jiraReporting) {
            this.jiraReporting = jiraReporting;
            return this;
        }

        public Builder jiraCycleIds(String jiraCycleIds) {
            this.jiraCycleIds = jiraCycleIds;
            return this;
        }

        public Builder jiraNewExecution(boolean jiraNewExecution) {
            this.jiraNewExecution = jiraNewExecution;
            return this;
        }

        public Builder cucumberTags(String cucumberTags) {
            this.cucumberTags = cucumberTags;
            return this;
        }

        public Builder testedFeature(String testedFeature) {
            this.testedFeature = testedFeature;
            return this;
        }

        public Builder testProperties(String testProperties) {
            this.testProperties = testProperties;
            return this;
        }

        public Builder browser(String browser) {
            this.browser = browser;
            return this;
        }

        public Builder browserVersion(String browserVersion) {
            this.browserVersion = browserVersion;
            return this;
        }

        public Builder appUrl(String appUrl) {
            this.appUrl = appUrl;
            return this;
        }

        public ImportConfig build() {
            return new ImportConfig(this);
        }
    }
}
