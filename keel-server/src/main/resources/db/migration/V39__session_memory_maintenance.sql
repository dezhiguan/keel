-- Optional operator-controlled per-session rollback/paired-acceptance override.
ALTER TABLE agent_session ADD COLUMN memory_maintenance_mode VARCHAR(8)
 CHECK (memory_maintenance_mode IN ('sync','async'));
-- Canonicalize historical display-name owner labels against the hashed console session.
DELETE FROM session_summary old USING agent_session s, session_summary canonical
 WHERE old.agent_name=s.agent_name AND old.env=s.env
 AND old.session_id=encode(sha256(convert_to(s.owner_user||':'||s.id,'UTF8')),'hex')
 AND old.owner_user<>s.owner_user AND canonical.agent_name=old.agent_name
 AND canonical.env=old.env AND canonical.session_id=old.session_id AND canonical.owner_user=s.owner_user;
UPDATE session_summary old SET owner_user=s.owner_user FROM agent_session s
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
