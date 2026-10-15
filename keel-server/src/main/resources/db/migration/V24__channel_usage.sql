ALTER TABLE channel DROP CONSTRAINT ck_channel_kind;
ALTER TABLE channel ADD CONSTRAINT ck_channel_kind CHECK (kind IN ('HOSTED', 'EMBED', 'WEB', 'MINIPROGRAM'));

CREATE TABLE channel_usage (
    channel_id BIGINT NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    hits INTEGER NOT NULL,
    PRIMARY KEY (channel_id, window_start)
);

CREATE INDEX idx_channel_usage_window ON channel_usage(window_start);

CREATE TABLE channel_feedback (
    id BIGSERIAL PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    agent_name VARCHAR(64) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    value SMALLINT NOT NULL CHECK (value IN (-1,1)),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (channel_id, trace_id)
);
