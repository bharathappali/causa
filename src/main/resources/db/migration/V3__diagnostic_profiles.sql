ALTER TABLE diagnostics ALTER COLUMN id             TYPE VARCHAR(32);
ALTER TABLE feedback    ALTER COLUMN diagnostics_id TYPE VARCHAR(32);
ALTER TABLE diagnostics ADD COLUMN profile_id   VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE diagnostics ADD COLUMN run_group_id VARCHAR(16);
CREATE UNIQUE INDEX IF NOT EXISTS uq_diagnostics_alert_profile ON diagnostics (alert_id, profile_id);
CREATE INDEX        IF NOT EXISTS idx_diagnostics_run_group    ON diagnostics (run_group_id);
