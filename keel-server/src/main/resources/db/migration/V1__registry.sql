-- registry 三张表，字段按技术文档第 06 节。其余九张表见 P1-3。

CREATE TABLE agent (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(64)  NOT NULL UNIQUE,
    display_name VARCHAR(128) NOT NULL,
    kind         VARCHAR(16)  NOT NULL,
    runtime      VARCHAR(16)  NOT NULL,
    language     VARCHAR(32),
    owner_org    VARCHAR(128) NOT NULL,
    owner_user   VARCHAR(64)  NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    liveness     VARCHAR(16)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_agent_kind CHECK (kind IN ('AGENT', 'SERVICE')),
    CONSTRAINT ck_agent_runtime CHECK (runtime IN ('code', 'dify')),
    CONSTRAINT ck_agent_status CHECK (status IN ('DRAFT', 'REGISTERED', 'ONLINE', 'DEGRADED', 'OFFLINE', 'RETIRED'))
);

CREATE TABLE agent_version (
    id            BIGSERIAL PRIMARY KEY,
    agent_name    VARCHAR(64)  NOT NULL REFERENCES agent (name),
    version       VARCHAR(32)  NOT NULL,
    env           VARCHAR(16)  NOT NULL,
    manifest_json JSONB        NOT NULL,
    manifest_hash VARCHAR(64)  NOT NULL,
    image         VARCHAR(256),
    gate_run_id   VARCHAR(64),
    released_by   VARCHAR(64)  NOT NULL,
    released_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_agent_version UNIQUE (agent_name, env, version),
    CONSTRAINT ck_agent_version_env CHECK (env IN ('dev', 'staging', 'prod'))
);

CREATE TABLE agent_instance (
    id           BIGSERIAL PRIMARY KEY,
    agent_name   VARCHAR(64)  NOT NULL REFERENCES agent (name),
    env          VARCHAR(16)  NOT NULL,
    instance_id  VARCHAR(128) NOT NULL,
    source       VARCHAR(16)  NOT NULL,
    version      VARCHAR(32),
    ready        BOOLEAN      NOT NULL,
    last_seen_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_agent_instance UNIQUE (agent_name, env, instance_id),
    CONSTRAINT ck_agent_instance_source CHECK (source IN ('k8s', 'heartbeat', 'probe'))
);
