-- Cucumber Test Results Database

CREATE TABLE IF NOT EXISTS runs (
    id                  INT AUTO_INCREMENT PRIMARY KEY,
    project             VARCHAR(100)    NOT NULL,
    project_type        VARCHAR(10)     NOT NULL DEFAULT 'web',
    run_at              DATETIME        NOT NULL,
    tests_total_ms      BIGINT,                         -- suma trvania vsetkych testov
    build_duration_ms   BIGINT,                         -- realny cas od zaciatku po koniec buildu
    triggered_by        VARCHAR(100),
    trigger_type        VARCHAR(20),
    branch              VARCHAR(100),
    commit_hash         VARCHAR(40),
    environment         VARCHAR(50),
    app_url             VARCHAR(500),
    build_number        VARCHAR(50),
    parallel_mode       VARCHAR(10)     DEFAULT 'serial',
    thread_count        INT             DEFAULT 1,
    jira_reporting      TINYINT(1)      DEFAULT 0,      -- 1 = ostra reportovanie (jiraDryRun=false)
    jira_new_execution  TINYINT(1)      DEFAULT 0,      -- 1 = vzdy vytvorit novu exekuciu
    cucumber_tags       VARCHAR(500),                   -- bamboo_tags: Cucumber tagy ktore sa pustia
    tested_feature      VARCHAR(500),                   -- bamboo_tested_feature: feature/y ktore sa testuju
    test_properties     VARCHAR(500),                   -- bamboo_test_properties: property file ktory sa nacita
    jira_cycle_ids      VARCHAR(500),                   -- Jira Test Cycle Keys (comma-separated)
    jira_cycle_ids      VARCHAR(500),                   -- comma-separated Jira cycle IDs

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
    tags                VARCHAR(500),

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
    line            INT,
    duration_ms     BIGINT,
    tags            VARCHAR(500),
    hook_error_type     VARCHAR(10),
    hook_error_message  TEXT,

    total_steps     INT             DEFAULT 0,
    passed_steps    INT             DEFAULT 0,
    failed_steps    INT             DEFAULT 0,
    skipped_steps   INT             DEFAULT 0,

    FOREIGN KEY (feature_id) REFERENCES features(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS steps (
    id                    INT AUTO_INCREMENT PRIMARY KEY,
    scenario_id           INT             NOT NULL,
    keyword               VARCHAR(20),
    name                  VARCHAR(500)    NOT NULL,
    translated_text       VARCHAR(500),
    line                  INT             NOT NULL,
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
