-- Optional operator-controlled per-session rollback/paired-acceptance override.
ALTER TABLE agent_session ADD COLUMN memory_maintenance_mode VARCHAR(8)
 CHECK (memory_maintenance_mode IN ('sync','async'));
-- Canonicalize historical display-name labels without losing a newer summary.
WITH winners AS (
 SELECT DISTINCT ON (old.agent_name,old.env,old.session_id)
 old.agent_name,old.env,s.owner_user,old.session_id,old.version,old.through_id,old.content,old.expires_at
 FROM session_summary old JOIN agent_session s ON old.agent_name=s.agent_name AND old.env=s.env
 AND old.session_id=encode(sha256(convert_to(s.owner_user||':'||s.id,'UTF8')),'hex')
 ORDER BY old.agent_name,old.env,old.session_id,old.through_id DESC,old.version DESC,old.expires_at DESC
)
INSERT INTO session_summary SELECT * FROM winners
ON CONFLICT(agent_name,env,owner_user,session_id) DO UPDATE
 SET version=CASE WHEN session_summary.content IS DISTINCT FROM EXCLUDED.content
 OR session_summary.through_id<>EXCLUDED.through_id THEN GREATEST(session_summary.version,EXCLUDED.version)+1
 ELSE GREATEST(session_summary.version,EXCLUDED.version) END,
 through_id=EXCLUDED.through_id,content=EXCLUDED.content,expires_at=EXCLUDED.expires_at;
DELETE FROM session_summary old USING agent_session s
 WHERE old.agent_name=s.agent_name AND old.env=s.env
 AND old.session_id=encode(sha256(convert_to(s.owner_user||':'||s.id,'UTF8')),'hex') AND old.owner_user<>s.owner_user;
-- Durable coalescing outbox; no user text, foreign keys or external queue dependency.
CREATE TABLE session_memory_job (
 id BIGSERIAL PRIMARY KEY,
 agent_name VARCHAR(64) NOT NULL,
 env VARCHAR(16) NOT NULL,
 owner_user VARCHAR(128) NOT NULL,
 session_id VARCHAR(128) NOT NULL,
 kind VARCHAR(16) NOT NULL CHECK (kind IN ('summary','facts')),
 desired_id BIGINT NOT NULL,
 target_id BIGINT NOT NULL DEFAULT 0,
 expected_version BIGINT NOT NULL DEFAULT 0,
 status VARCHAR(16) NOT NULL DEFAULT 'pending',
 lease_token VARCHAR(64),
 lease_until TIMESTAMPTZ,
 attempts INTEGER NOT NULL DEFAULT 0,
 calls INTEGER NOT NULL DEFAULT 0,
 cost_upper_bound NUMERIC(16,8) NOT NULL DEFAULT 0,
 reserved_cny NUMERIC(16,8) NOT NULL DEFAULT 0,
 trigger_trace_id VARCHAR(64),
 worker_trace_id VARCHAR(64),
 reason VARCHAR(64),
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(agent_name,env,owner_user,session_id,kind)
);
CREATE INDEX ix_session_memory_claim ON session_memory_job(agent_name,env,status,next_attempt_at);

-- Attempt history keeps queue latency and crash/duplicate evidence after coalescing.
CREATE TABLE session_memory_attempt (
 lease_token VARCHAR(64) PRIMARY KEY,
 job_id BIGINT NOT NULL,
 agent_name VARCHAR(64) NOT NULL,
 env VARCHAR(16) NOT NULL,
 owner_user VARCHAR(128) NOT NULL,
 session_id VARCHAR(128) NOT NULL,
 kind VARCHAR(16) NOT NULL,
 target_id BIGINT NOT NULL,
 expected_version BIGINT NOT NULL,
 attempt INTEGER NOT NULL,
 worker_instance VARCHAR(128) NOT NULL DEFAULT '',
 trigger_trace_id VARCHAR(64),
 worker_trace_id VARCHAR(64),
 queued_at TIMESTAMPTZ NOT NULL,
 started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 finished_at TIMESTAMPTZ,
 status VARCHAR(16) NOT NULL DEFAULT 'running',
 reason VARCHAR(64),
 through_id BIGINT,
 more BOOLEAN,
 calls INTEGER,
 input_tokens INTEGER,
 output_tokens INTEGER,
 reserved_cny NUMERIC(16,8) NOT NULL DEFAULT 0.5,
 cost_upper_bound NUMERIC(16,8)
);
CREATE INDEX ix_session_memory_attempt_scope ON session_memory_attempt(agent_name,env,owner_user,session_id,started_at);
