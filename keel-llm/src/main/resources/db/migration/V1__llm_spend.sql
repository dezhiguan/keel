CREATE TABLE llm_spend (
    attempt_id TEXT PRIMARY KEY,
    request_id TEXT NOT NULL,
    alias TEXT NOT NULL,
    model TEXT NOT NULL,
    status TEXT NOT NULL,
    input_tokens INTEGER,
    output_tokens INTEGER,
    cache_tokens INTEGER,
    cost_cny NUMERIC(18, 6),
    reserved_cny NUMERIC(18, 6) NOT NULL DEFAULT 0,
    price_version TEXT NOT NULL DEFAULT '',
    route_version TEXT NOT NULL DEFAULT '',
    trace_id TEXT NOT NULL DEFAULT '',
    latency_ms BIGINT NOT NULL DEFAULT 0,
    timed_out BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX llm_spend_cursor ON llm_spend (created_at DESC, attempt_id DESC);
CREATE INDEX llm_spend_alias_cursor ON llm_spend (alias, created_at DESC, attempt_id DESC);
