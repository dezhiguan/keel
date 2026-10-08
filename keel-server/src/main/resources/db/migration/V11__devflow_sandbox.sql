CREATE SEQUENCE devflow_sandbox_seq;

CREATE TABLE devflow_sandbox_run (
    run_id      VARCHAR(16)  PRIMARY KEY,
    job_id      VARCHAR(16)  NOT NULL REFERENCES devflow_job (job_id),
    repo        VARCHAR(64)  NOT NULL,
    git_ref     VARCHAR(256) NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    report      TEXT,
    truncated   BOOLEAN      NOT NULL,
    actor       VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deadline_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_devflow_sandbox_status CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED', 'TIMEOUT'))
);
CREATE INDEX ix_devflow_sandbox_active ON devflow_sandbox_run (status, created_at);
