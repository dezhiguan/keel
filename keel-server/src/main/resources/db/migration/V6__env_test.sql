-- 环境增加 test。审批单记下环境，列表才能按环境分开。

ALTER TABLE agent_version DROP CONSTRAINT ck_agent_version_env;
ALTER TABLE agent_version ADD CONSTRAINT ck_agent_version_env
    CHECK (env IN ('dev', 'test', 'staging', 'prod'));

ALTER TABLE agent_resource DROP CONSTRAINT ck_agent_resource_env;
ALTER TABLE agent_resource ADD CONSTRAINT ck_agent_resource_env
    CHECK (env IN ('dev', 'test', 'staging', 'prod'));

ALTER TABLE reconcile_finding DROP CONSTRAINT ck_reconcile_finding_env;
ALTER TABLE reconcile_finding ADD CONSTRAINT ck_reconcile_finding_env
    CHECK (env IN ('dev', 'test', 'staging', 'prod'));

ALTER TABLE agent_run DROP CONSTRAINT ck_agent_run_env;
ALTER TABLE agent_run ADD CONSTRAINT ck_agent_run_env
    CHECK (env IN ('dev', 'test', 'staging', 'prod'));

ALTER TABLE release_record DROP CONSTRAINT ck_release_record_env;
ALTER TABLE release_record ADD CONSTRAINT ck_release_record_env
    CHECK (env IN ('dev', 'test', 'staging', 'prod'));

ALTER TABLE approval_request ADD COLUMN env VARCHAR(16) NOT NULL DEFAULT 'prod';

UPDATE approval_request AS request
SET env = run.env
FROM agent_run AS run
WHERE request.run_id = run.run_id;

ALTER TABLE approval_request ADD CONSTRAINT ck_approval_request_env
    CHECK (env IN ('dev', 'test', 'staging', 'prod'));
