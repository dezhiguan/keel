-- 控制台在 keel-audit 未接入时仍要能查出注册和探针。
-- 只 INSERT / SELECT。不要对这两张表写 UPDATE / DELETE。

CREATE TABLE console_audit_event (
    event_id     VARCHAR(64)  NOT NULL,
    agent        VARCHAR(64)  NOT NULL,
    env          VARCHAR(16)  NOT NULL,
    ts           TIMESTAMPTZ  NOT NULL,
    action       VARCHAR(32)  NOT NULL,
    risk         VARCHAR(8)   NOT NULL,
    decision     VARCHAR(16)  NOT NULL,
    resource     VARCHAR(256),
    trace_id     VARCHAR(64),
    actor_user   VARCHAR(128),
    payload      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    input_digest VARCHAR(64),
    prev_hash    VARCHAR(64)  NOT NULL,
    hash         VARCHAR(64)  NOT NULL,
    PRIMARY KEY (agent, ts, event_id)
);
CREATE INDEX ix_console_audit_event_ts ON console_audit_event (ts DESC);

CREATE TABLE invoke_trace (
    trace_id    VARCHAR(64) PRIMARY KEY,
    agent       VARCHAR(64)  NOT NULL,
    env         VARCHAR(16)  NOT NULL,
    question    TEXT         NOT NULL,
    started_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    duration_ms INT          NOT NULL,
    status      VARCHAR(16)  NOT NULL
);
CREATE INDEX ix_invoke_trace_started ON invoke_trace (started_at DESC);
