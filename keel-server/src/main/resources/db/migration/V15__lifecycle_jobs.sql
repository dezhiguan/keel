CREATE TABLE agent_self_check (
    agent_name VARCHAR(64) NOT NULL,
    env VARCHAR(16) NOT NULL,
    manifest_hash VARCHAR(64) NOT NULL,
    report JSONB NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (agent_name, env)
);

CREATE TABLE eval_run (
    run_id VARCHAR(64) PRIMARY KEY,
    agent_name VARCHAR(64) NOT NULL,
    env VARCHAR(16) NOT NULL,
    namespace VARCHAR(64) NOT NULL,
    source_pod VARCHAR(128) NOT NULL,
    image TEXT NOT NULL,
    endpoint TEXT NOT NULL,
    dataset TEXT NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    result JSONB,
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_eval_run_state CHECK (state IN ('QUEUED','RUNNING','DONE','FAILED'))
);
CREATE UNIQUE INDEX uq_eval_run_active ON eval_run(agent_name, env) WHERE state IN ('QUEUED','RUNNING');
CREATE INDEX ix_eval_run_latest ON eval_run(agent_name, created_at DESC);
