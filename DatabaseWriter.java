package sk.moja.db.importer.db;

import sk.moja.db.importer.config.ImportConfig;
import sk.moja.db.importer.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.LinkedHashMap;
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
                    insertSteps(scenarioId, scenario);
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
                project, project_type, run_at,
                duration_ms, wall_clock_ms,
                triggered_by, trigger_type,
                branch, commit_hash, commit_message,
                environment, app_url, build_number,
                total_features,  passed_features,  failed_features,
                total_scenarios, passed_scenarios, failed_scenarios,
                total_steps,     passed_steps,     failed_steps,     skipped_steps
            ) VALUES (?, 'web', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        //       1          2    3  4  5  6  7  8  9  10 11 12 13 14 15 16 17 18 19 20 21 22 23

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1,    config.project);
            ps.setTimestamp(2, Timestamp.valueOf(run.runAt));
            ps.setLong(3,      run.durationMs);
            ps.setLong(4,      run.wallClockMs);
            ps.setString(5,    config.triggeredBy);
            ps.setString(6,    config.triggerType);
            ps.setString(7,    config.branch);
            ps.setString(8,    config.commitHash);
            ps.setString(9,    config.commitMessage);
            ps.setString(10,   config.environment);
            ps.setString(11,   config.appUrl);
            ps.setString(12,   config.buildNumber);
            ps.setInt(13,      run.totalFeatures);
            ps.setInt(14,      run.passedFeatures);
            ps.setInt(15,      run.failedFeatures);
            ps.setInt(16,      run.totalScenarios);
            ps.setInt(17,      run.passedScenarios);
            ps.setInt(18,      run.failedScenarios);
            ps.setInt(19,      run.totalSteps);
            ps.setInt(20,      run.passedSteps);
            ps.setInt(21,      run.failedSteps);
            ps.setInt(22,      run.skippedSteps);
            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            return keys.getLong(1);
        }
    }

    private void insertRunMetadata(long runId, ImportConfig config) throws SQLException {
        // Ordered map — browser metadata
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("browser",         config.browser);
        metadata.put("browser_version", config.browserVersion);

        String sql = "INSERT INTO run_metadata (run_id, meta_key, meta_value) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                ps.setLong(1,   runId);
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
                run_id, name, file_path, status, duration_ms, tags,
                total_scenarios, passed_scenarios, failed_scenarios
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        //       1   2   3    4    5    6   7   8    9

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1,   runId);
            ps.setString(2, feature.name);
            ps.setString(3, feature.filePath);
            ps.setString(4, feature.status);
            ps.setLong(5,   feature.durationMs);
            ps.setString(6, feature.tags);
            ps.setInt(7,    feature.totalScenarios);
            ps.setInt(8,    feature.passedScenarios);
            ps.setInt(9,    feature.failedScenarios);
            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            return keys.getLong(1);
        }
    }

    private long insertScenario(long featureId, Scenario scenario) throws SQLException {
        String sql = """
            INSERT INTO scenarios (
                feature_id, name, status, line, duration_ms, tags,
                hook_error_type, hook_error_message,
                total_steps, passed_steps, failed_steps, skipped_steps
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        //       1    2   3    4    5    6   7   8    9   10   11   12

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1,   featureId);
            ps.setString(2, scenario.name);
            ps.setString(3, scenario.status);
            ps.setInt(4,    scenario.line);
            ps.setLong(5,   scenario.durationMs);
            ps.setString(6, scenario.tags);
            ps.setString(7, scenario.hookErrorType);
            ps.setString(8, scenario.hookErrorMessage);
            ps.setInt(9,    scenario.totalSteps);
            ps.setInt(10,   scenario.passedSteps);
            ps.setInt(11,   scenario.failedSteps);
            ps.setInt(12,   scenario.skippedSteps);
            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            return keys.getLong(1);
        }
    }

    private void insertSteps(long scenarioId, Scenario scenario) throws SQLException {
        String sql = """
            INSERT INTO steps (
                scenario_id, keyword, name, translated_text,
                line, status, duration_ms,
                error_message, stack_trace,
                screenshot_url, failed_screenshot_url
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        //       1    2   3    4    5    6    7    8    9   10   11

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Step step : scenario.steps) {
                ps.setLong(1,   scenarioId);
                ps.setString(2, step.keyword);
                ps.setString(3, step.name);
                ps.setString(4, step.translatedText);
                ps.setInt(5,    step.line);
                ps.setString(6, step.status);
                ps.setLong(7,   step.durationMs);
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
