ALTER TABLE runs CHANGE duration_ms   tests_total_ms    BIGINT;
ALTER TABLE runs CHANGE wall_clock_ms build_duration_ms BIGINT;
ALTER TABLE runs DROP   COLUMN commit_message;
ALTER TABLE runs ADD    COLUMN jira_reporting TINYINT(1)   DEFAULT 0;
ALTER TABLE runs ADD    COLUMN jira_cycle_ids VARCHAR(500);
ALTER TABLE runs ADD COLUMN jira_new_execution TINYINT(1) DEFAULT 0;
