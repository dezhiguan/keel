CREATE TABLE skill (
    name        VARCHAR(64) PRIMARY KEY,
    owner_org   VARCHAR(128) NOT NULL,
    description TEXT         NOT NULL,
    source      VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_skill_source CHECK (source IN ('AUTHORED', 'EXPERIENCE'))
);

CREATE TABLE skill_version (
    id             BIGSERIAL PRIMARY KEY,
    skill_name     VARCHAR(64) NOT NULL REFERENCES skill (name),
    version        INTEGER     NOT NULL,
    instructions   TEXT        NOT NULL,
    tools_json     JSONB       NOT NULL,
    examples_json  JSONB       NOT NULL,
    cases_json     JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_skill_version UNIQUE (skill_name, version)
);

CREATE TABLE agent_skill_grant (
    id          BIGSERIAL PRIMARY KEY,
    agent_name  VARCHAR(64) NOT NULL REFERENCES agent (name),
    skill_name  VARCHAR(64) NOT NULL REFERENCES skill (name),
    version     INTEGER     NOT NULL,
    status      VARCHAR(16) NOT NULL,
    granted_by  VARCHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_agent_skill_grant UNIQUE (agent_name, skill_name),
    CONSTRAINT ck_agent_skill_grant_status CHECK (status IN ('GRANTED'))
);
CREATE INDEX ix_agent_skill_grant_skill ON agent_skill_grant (skill_name);
