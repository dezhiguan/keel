CREATE TABLE devflow_execution (
    job_id VARCHAR(16) PRIMARY KEY REFERENCES devflow_job(job_id),
    state_json JSONB NOT NULL,
    action VARCHAR(16) NOT NULL,
    lease_token VARCHAR(64),
    lease_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_devflow_execution_queue ON devflow_execution ((state_json->>'phase'), lease_until);
