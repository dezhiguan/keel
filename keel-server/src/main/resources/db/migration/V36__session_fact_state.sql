CREATE TABLE session_fact_state (
    agent_name VARCHAR(64) NOT NULL,
    env VARCHAR(16) NOT NULL,
    owner_user VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL,
    cursor_id BIGINT NOT NULL,
    state JSONB NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (agent_name, env, owner_user, session_id)
);
CREATE INDEX ix_session_fact_expiry ON session_fact_state(expires_at);
