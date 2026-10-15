CREATE TABLE user_memory (
    id          BIGSERIAL PRIMARY KEY,
    agent_name  VARCHAR(64)  NOT NULL REFERENCES agent (name),
    owner_user  VARCHAR(128) NOT NULL,
    content     TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_user_memory_owner ON user_memory (agent_name, owner_user);
