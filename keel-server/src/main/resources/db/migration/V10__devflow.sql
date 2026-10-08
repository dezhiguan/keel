CREATE SEQUENCE devflow_job_seq;
CREATE SEQUENCE devflow_batch_seq;

CREATE TABLE devflow_job (
    job_id          VARCHAR(16)    PRIMARY KEY,
    batch_id        VARCHAR(16),
    layer           VARCHAR(8)     NOT NULL,
    kind            VARCHAR(8)     NOT NULL,
    mode            VARCHAR(16)    NOT NULL,
    title           VARCHAR(256)   NOT NULL,
    requester       VARCHAR(128)   NOT NULL,
    owner_org       VARCHAR(128)   NOT NULL,
    target_agent    VARCHAR(64)    NOT NULL,
    producer_agent  VARCHAR(64)    NOT NULL,
    template        VARCHAR(64)    NOT NULL,
    status          VARCHAR(16)    NOT NULL,
    stage           VARCHAR(16)    NOT NULL,
    human_dev_user  VARCHAR(128),
    budget_cny      NUMERIC(12, 2) NOT NULL,
    spent_cny       NUMERIC(12, 2) NOT NULL,
    fix_rounds      INTEGER        NOT NULL,
    max_fix_rounds  INTEGER        NOT NULL,
    need_review     BOOLEAN        NOT NULL,
    watch_day       INTEGER,
    goal            TEXT           NOT NULL,
    tools_json      JSONB          NOT NULL,
    knowledge_json  JSONB          NOT NULL,
    seed_json       JSONB          NOT NULL,
    events_json     JSONB          NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_devflow_job_layer CHECK (layer IN ('META', 'DEV', 'BIZ')),
    CONSTRAINT ck_devflow_job_kind CHECK (kind IN ('CREATE', 'CHANGE')),
    CONSTRAINT ck_devflow_job_mode CHECK (mode IN ('AUTO', 'COLLAB', 'SCAFFOLD')),
    CONSTRAINT ck_devflow_job_status CHECK (status IN ('RUN', 'WAIT', 'HUMAN', 'QUEUED', 'DONE', 'FAIL', 'CANCEL')),
    CONSTRAINT ck_devflow_job_stage CHECK (stage IN ('SPEC', 'H1', 'EVAL', 'H2', 'H3', 'BUILD', 'REVIEW', 'GATE', 'H4', 'RELEASE', 'WATCH')),
    CONSTRAINT ck_devflow_job_dev CHECK (layer <> 'DEV' OR (producer_agent = 'meta-agent' AND mode = 'COLLAB'))
);
CREATE INDEX ix_devflow_job_status ON devflow_job (status, stage);

CREATE TABLE devflow_batch (
    batch_id      VARCHAR(16)  PRIMARY KEY,
    title         VARCHAR(256) NOT NULL,
    requester     VARCHAR(128) NOT NULL,
    concurrency   INTEGER      NOT NULL,
    pilot_job_id  VARCHAR(16)  NOT NULL,
    pilot_passed  BOOLEAN      NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE devflow_stage_run (
    id         BIGSERIAL PRIMARY KEY,
    job_id     VARCHAR(16)  NOT NULL REFERENCES devflow_job (job_id),
    stage      VARCHAR(16)  NOT NULL,
    actor      VARCHAR(128) NOT NULL,
    attempt    INTEGER      NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    trace_id   VARCHAR(64),
    cost_cny   NUMERIC(12, 2),
    summary    TEXT,
    started_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ended_at   TIMESTAMPTZ
);

CREATE TABLE devflow_artifact (
    id         BIGSERIAL PRIMARY KEY,
    job_id     VARCHAR(16) NOT NULL REFERENCES devflow_job (job_id),
    kind       VARCHAR(16) NOT NULL,
    ref        TEXT        NOT NULL,
    sha256     VARCHAR(64),
    origin     VARCHAR(8)  NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_devflow_artifact_kind CHECK (kind IN ('SPEC', 'MANIFEST', 'CODE', 'EVALSET', 'REVIEW', 'GATE_REPORT')),
    CONSTRAINT ck_devflow_artifact_origin CHECK (origin IN ('HUMAN', 'AGENT'))
);

CREATE TABLE devflow_holdout (
    job_id        VARCHAR(16)  NOT NULL REFERENCES devflow_job (job_id),
    case_id       VARCHAR(64)  NOT NULL,
    input_json    JSONB        NOT NULL,
    expected_json JSONB        NOT NULL,
    tags          JSONB        NOT NULL,
    created_by    VARCHAR(128) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, case_id)
);

CREATE TABLE devflow_setting (
    key         VARCHAR(64) PRIMARY KEY,
    value_json  JSONB       NOT NULL,
    updated_by  VARCHAR(128),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE agent ADD COLUMN layer VARCHAR(8);
ALTER TABLE agent ADD COLUMN devflow_job_id VARCHAR(16);
ALTER TABLE agent ADD CONSTRAINT ck_agent_layer CHECK (layer IS NULL OR layer IN ('META', 'DEV', 'BIZ'));

ALTER TABLE approval_request ADD COLUMN devflow_job_id VARCHAR(16);
ALTER TABLE approval_request ADD COLUMN devflow_gate VARCHAR(8);
ALTER TABLE approval_request ADD CONSTRAINT ck_approval_devflow_gate CHECK (devflow_gate IS NULL OR devflow_gate IN ('H1', 'H2', 'H4'));

ALTER TABLE agent_run ADD COLUMN devflow_job_id VARCHAR(16);
ALTER TABLE agent_run ADD COLUMN devflow_gate VARCHAR(8);
ALTER TABLE agent_run ADD CONSTRAINT ck_agent_run_devflow_gate CHECK (devflow_gate IS NULL OR devflow_gate IN ('H1', 'H2', 'H4'));
