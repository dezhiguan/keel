-- keel 库的其余业务表。V1 registry 迁移保持不变。

CREATE TABLE agent_resource (
    id          BIGSERIAL PRIMARY KEY,
    agent_name  VARCHAR(64)  NOT NULL REFERENCES agent (name),
    env         VARCHAR(16)  NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    external_id VARCHAR(256) NOT NULL,
    status      VARCHAR(32)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_agent_resource_env CHECK (env IN ('dev', 'staging', 'prod')),
    CONSTRAINT ck_agent_resource_type CHECK (type IN ('litellm_key', 'langfuse', 'secret', 'dataset')),
    CONSTRAINT uk_agent_resource UNIQUE (agent_name, env, type, external_id)
);
CREATE INDEX ix_agent_resource_owner ON agent_resource (agent_name, env);

CREATE TABLE reconcile_finding (
    id          BIGSERIAL PRIMARY KEY,
    agent_name  VARCHAR(64) NOT NULL,
    env         VARCHAR(16) NOT NULL,
    kind        VARCHAR(32) NOT NULL,
    detail      JSONB       NOT NULL,
    first_seen  TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT ck_reconcile_finding_env CHECK (env IN ('dev', 'staging', 'prod')),
    CONSTRAINT ck_reconcile_finding_kind CHECK (kind IN ('OFFLINE', 'UNREGISTERED', 'ZOMBIE', 'RETIRE_INCOMPLETE', 'VERSION_MISMATCH'))
);
CREATE INDEX ix_reconcile_finding_open ON reconcile_finding (agent_name, env, first_seen DESC)
    WHERE resolved_at IS NULL;

CREATE TABLE tool (
    id                 BIGSERIAL PRIMARY KEY,
    name               VARCHAR(128) NOT NULL UNIQUE,
    scope              VARCHAR(16)  NOT NULL,
    owner_agent        VARCHAR(64) REFERENCES agent (name),
    owner_org          VARCHAR(128) NOT NULL,
    provider           VARCHAR(512) NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    replaced_by        VARCHAR(128),
    deprecate_deadline TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_tool_scope CHECK (scope IN ('PRIVATE', 'SHARED')),
    CONSTRAINT ck_tool_status CHECK (status IN ('REGISTERED', 'ONLINE', 'DEPRECATED', 'RETIRED'))
);
CREATE INDEX ix_tool_owner ON tool (owner_agent);

CREATE TABLE approval_policy (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    subject_type    VARCHAR(32)  NOT NULL,
    approver_type   VARCHAR(16)  NOT NULL,
    approver_ref    VARCHAR(128) NOT NULL,
    condition_expr  TEXT,
    timeout_minutes INTEGER      NOT NULL,
    cooldown_hours  INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT ck_approval_policy_subject CHECK (subject_type IN ('tool.call', 'tool.config', 'agent.config', 'agent.retire', 'data.export')),
    CONSTRAINT ck_approval_policy_approver CHECK (approver_type IN ('ROLE', 'USER', 'OWNER')),
    CONSTRAINT ck_approval_policy_timeout CHECK (timeout_minutes > 0),
    CONSTRAINT ck_approval_policy_cooldown CHECK (cooldown_hours >= 0)
);
CREATE INDEX ix_approval_policy_subject ON approval_policy (subject_type);

CREATE TABLE tool_version (
    id                 BIGSERIAL PRIMARY KEY,
    tool_name          VARCHAR(128) NOT NULL REFERENCES tool (name),
    version            VARCHAR(32)  NOT NULL,
    schema_json        JSONB        NOT NULL,
    description        TEXT         NOT NULL,
    access             VARCHAR(16)  NOT NULL,
    risk               VARCHAR(16)  NOT NULL,
    approval_policy_id BIGINT REFERENCES approval_policy (id),
    breaking           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_tool_version UNIQUE (tool_name, version),
    CONSTRAINT ck_tool_version_access CHECK (access IN ('READ', 'WRITE', 'EXEC')),
    CONSTRAINT ck_tool_version_risk CHECK (risk IN ('LOW', 'MID', 'HIGH'))
);

CREATE TABLE agent_tool_grant (
    id            BIGSERIAL PRIMARY KEY,
    agent_name    VARCHAR(64)  NOT NULL REFERENCES agent (name),
    tool_name     VARCHAR(128) NOT NULL REFERENCES tool (name),
    version_range VARCHAR(128) NOT NULL,
    granted_by    VARCHAR(64)  NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_agent_tool_grant UNIQUE (agent_name, tool_name, version_range)
);
CREATE INDEX ix_agent_tool_grant_tool ON agent_tool_grant (tool_name, status);

CREATE TABLE agent_run (
    run_id          VARCHAR(64) PRIMARY KEY,
    agent_name      VARCHAR(64) NOT NULL REFERENCES agent (name),
    env             VARCHAR(16) NOT NULL,
    trace_id        VARCHAR(64) NOT NULL,
    session_id      VARCHAR(128),
    status          VARCHAR(16) NOT NULL,
    suspend_reason  VARCHAR(32),
    suspend_ref     VARCHAR(128),
    checkpoint_ref  VARCHAR(512),
    idempotency_key VARCHAR(128),
    actor_user      VARCHAR(128),
    deadline        TIMESTAMPTZ,
    resumed_count   INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_agent_run_idempotency UNIQUE (agent_name, idempotency_key),
    CONSTRAINT ck_agent_run_env CHECK (env IN ('dev', 'staging', 'prod')),
    CONSTRAINT ck_agent_run_status CHECK (status IN ('RUNNING', 'SUSPENDED', 'DONE', 'FAILED', 'EXPIRED')),
    CONSTRAINT ck_agent_run_reason CHECK (suspend_reason IS NULL OR suspend_reason IN ('approval', 'input_required', 'handoff')),
    CONSTRAINT ck_agent_run_resumed_count CHECK (resumed_count >= 0)
);
CREATE INDEX ix_agent_run_status_deadline ON agent_run (status, deadline) WHERE status = 'SUSPENDED';
CREATE INDEX ix_agent_run_agent_created ON agent_run (agent_name, created_at DESC);

CREATE TABLE approval_request (
    id             BIGSERIAL PRIMARY KEY,
    subject_type   VARCHAR(32)  NOT NULL,
    subject_ref    VARCHAR(128) NOT NULL,
    agent_name     VARCHAR(64) REFERENCES agent (name),
    run_id         VARCHAR(64) REFERENCES agent_run (run_id),
    trace_id       VARCHAR(64),
    actor_user     VARCHAR(128) NOT NULL,
    payload_digest VARCHAR(64),
    summary        TEXT         NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    decided_by     VARCHAR(128),
    decided_at     TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_approval_request_subject CHECK (subject_type IN ('tool.call', 'tool.config', 'agent.config', 'agent.retire', 'data.export')),
    CONSTRAINT ck_approval_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED'))
);
CREATE INDEX ix_approval_request_pending ON approval_request (subject_type, created_at)
    WHERE status = 'PENDING';
CREATE INDEX ix_approval_request_run ON approval_request (run_id) WHERE run_id IS NOT NULL;

CREATE TABLE release_record (
    id                   BIGSERIAL PRIMARY KEY,
    agent_name           VARCHAR(64) NOT NULL REFERENCES agent (name),
    version              VARCHAR(32) NOT NULL,
    env                  VARCHAR(16) NOT NULL,
    gate_passed          BOOLEAN NOT NULL,
    score_total          NUMERIC(8, 4),
    score_by_tag_json    JSONB,
    prompt_versions_json JSONB,
    ci_run_url           VARCHAR(1024),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_release_record_env CHECK (env IN ('dev', 'staging', 'prod'))
);
CREATE INDEX ix_release_record_agent ON release_record (agent_name, env, created_at DESC);

CREATE TABLE route_snapshot (
    version       BIGSERIAL PRIMARY KEY,
    changed_agent VARCHAR(64) NOT NULL REFERENCES agent (name),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
