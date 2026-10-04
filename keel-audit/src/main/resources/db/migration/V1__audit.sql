-- keel_audit. The application role keel_audit_app may INSERT and SELECT audit_event only.
-- Chain movement is UPDATE on audit_chain_head, not on audit_event.

CREATE TABLE audit_event (
    event_id       VARCHAR(64)  NOT NULL,
    agent          VARCHAR(64)  NOT NULL,
    ts             TIMESTAMPTZ  NOT NULL,
    canonical_json TEXT         NOT NULL,
    prev_hash      VARCHAR(64)  NOT NULL,
    hash           VARCHAR(64)  NOT NULL,
    PRIMARY KEY (agent, ts, event_id)
) PARTITION BY RANGE (ts);

CREATE TABLE audit_event_2026_10 PARTITION OF audit_event
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE audit_event_2026_11 PARTITION OF audit_event
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE audit_event_2026_12 PARTITION OF audit_event
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');

CREATE TABLE audit_chain_head (
    agent VARCHAR(64) PRIMARY KEY,
    hash  VARCHAR(64) NOT NULL,
    count BIGINT      NOT NULL
);

CREATE TABLE audit_archive (
    agent      VARCHAR(64) NOT NULL,
    month      VARCHAR(7)  NOT NULL,
    sha256     VARCHAR(64) NOT NULL,
    object_key TEXT        NOT NULL,
    detached_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (agent, month)
);

CREATE TABLE audit_export (
    export_id   VARCHAR(64) PRIMARY KEY,
    agent       VARCHAR(64) NOT NULL,
    approval_id VARCHAR(64),
    status      VARCHAR(32) NOT NULL,
    file_path   TEXT
);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keel_audit_app') THEN
        REVOKE UPDATE, DELETE ON audit_event FROM keel_audit_app;
        GRANT INSERT, SELECT ON audit_event TO keel_audit_app;
        GRANT SELECT, INSERT, UPDATE ON audit_chain_head TO keel_audit_app;
        GRANT SELECT ON audit_archive TO keel_audit_app;
        GRANT SELECT, INSERT ON audit_export TO keel_audit_app;
    END IF;
END $$;
