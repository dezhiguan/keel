CREATE TABLE agent_session (
    id          VARCHAR(128) PRIMARY KEY,
    agent_name  VARCHAR(64)  NOT NULL,
    env         VARCHAR(16)  NOT NULL,
    owner_user  VARCHAR(128) NOT NULL,
    title       VARCHAR(64)  NOT NULL DEFAULT '',
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_agent_session_owner ON agent_session (owner_user, agent_name, env, updated_at DESC);

CREATE TABLE session_message (
    id          BIGSERIAL PRIMARY KEY,
    session_id  VARCHAR(128) NOT NULL,
    agent_name  VARCHAR(64)  NOT NULL,
    owner_user  VARCHAR(128) NOT NULL,
    role        VARCHAR(16)  NOT NULL,
    content     TEXT         NOT NULL,
    trace_id    VARCHAR(128),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_session_message_session ON session_message (session_id, id);
