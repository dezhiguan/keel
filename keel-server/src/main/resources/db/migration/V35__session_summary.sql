CREATE TABLE session_summary (
    agent_name VARCHAR(64) NOT NULL,
    env VARCHAR(16) NOT NULL,
    owner_user VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL,
    through_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (agent_name, env, owner_user, session_id)
);
CREATE INDEX ix_session_summary_expiry ON session_summary(expires_at);
