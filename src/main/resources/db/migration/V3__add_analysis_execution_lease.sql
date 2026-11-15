-- Optional for historic/non-worker runs; managed jobs always set both values.
ALTER TABLE analysis_run
  ADD COLUMN execution_owner uuid,
  ADD COLUMN execution_expires_at timestamptz,
  ADD CONSTRAINT analysis_run_lease_pair_check CHECK (
    (execution_owner IS NULL) = (execution_expires_at IS NULL)),
  ADD CONSTRAINT analysis_run_lease_deadline_check CHECK (
    execution_expires_at IS NULL OR execution_expires_at > created_at);

CREATE INDEX analysis_run_active_expiry_idx ON analysis_run (execution_expires_at)
  WHERE execution_status IN ('PENDING', 'RUNNING');
