-- Shared, restart-safe readers. Lease owner fences delayed writers after a takeover.
CREATE TABLE insight_snapshot (
    snapshot_key VARCHAR(120) PRIMARY KEY,
    payload JSONB,
    updated_at TIMESTAMPTZ,
    retry_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_owner VARCHAR(64),
    failed BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE insight_observation (
    observation_id VARCHAR(128) PRIMARY KEY,
    trace_id VARCHAR(128) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL
);
CREATE INDEX ix_insight_observation_trace ON insight_observation(trace_id);
CREATE INDEX ix_insight_observation_started ON insight_observation(started_at);
CREATE TABLE insight_trace (
    trace_id VARCHAR(128) PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL
);
CREATE INDEX ix_insight_trace_started ON insight_trace(started_at DESC, trace_id);
CREATE INDEX ix_insight_trace_env_started ON insight_trace((payload->>'env'), started_at DESC);
CREATE INDEX ix_insight_trace_job ON insight_trace((payload->>'devflowJobId'));
CREATE INDEX ix_insight_trace_agents ON insight_trace USING gin ((payload->'agents'));
CREATE TABLE insight_trace_bill (
    trace_id VARCHAR(128) PRIMARY KEY,
    payload JSONB NOT NULL
);
-- Current run overlay must not scan agent_run once for every indexed trace.
CREATE INDEX ix_agent_run_trace_created ON agent_run(trace_id, created_at DESC);
