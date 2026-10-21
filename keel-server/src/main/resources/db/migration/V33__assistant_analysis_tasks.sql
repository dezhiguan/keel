CREATE TABLE assistant_analysis_scope (
  agent_name text NOT NULL, environment text NOT NULL CHECK(environment='staging'),
  owner_org text NOT NULL, enabled boolean NOT NULL, granted_by text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(agent_name,environment)
);
CREATE TABLE assistant_analysis_task (
  task_id text PRIMARY KEY, agent_name text NOT NULL CHECK(agent_name='keel-assistant'),
  environment text NOT NULL CHECK(environment='staging'), owner_user text NOT NULL,
  owner_username text NOT NULL, owner_org text NOT NULL, session_id text NOT NULL,
  request_id text NOT NULL, namespace text NOT NULL DEFAULT 'user', input_digest text NOT NULL, original_goal text NOT NULL,
  material text NOT NULL, authorization_ref text NOT NULL, authorized_until timestamptz NOT NULL,
  state text NOT NULL CHECK(state IN ('queued','running','waiting_authorization','validating',
    'succeeded','failed','cancel_requested','cancelled','unknown')),
  revision integer NOT NULL DEFAULT 1, attempt integer NOT NULL DEFAULT 0 CHECK(attempt BETWEEN 0 AND 2),
  fence bigint NOT NULL DEFAULT 0, lease_owner text, lease_until timestamptz,
  intent_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  report text, acceptance jsonb, error text, parent_trace_id text, execution_trace_id text,
  execution_run_id text, prompt_version integer, gateway_request_id text, cost_cny numeric, usage jsonb,
  UNIQUE(agent_name,environment,owner_user,namespace,request_id)
);
CREATE INDEX assistant_analysis_queue ON assistant_analysis_task(created_at) WHERE state='queued';
CREATE INDEX assistant_analysis_owner ON assistant_analysis_task(owner_user,agent_name,environment,created_at DESC);
CREATE TABLE assistant_analysis_attempt (
  task_id text NOT NULL, attempt integer NOT NULL, fence bigint NOT NULL,
  intent_at timestamptz NOT NULL DEFAULT now(), state text NOT NULL,
  report text, acceptance jsonb, error text, execution_trace_id text, execution_run_id text,
  prompt_version integer, gateway_request_id text, cost_cny numeric, usage jsonb,
  PRIMARY KEY(task_id,attempt)
);
INSERT INTO tool(name,scope,owner_org,provider,status) VALUES
 ('keel.tasks.command','SHARED','平台组','keel','ONLINE'),
 ('keel.tasks.execution','SHARED','平台组','keel','ONLINE') ON CONFLICT(name) DO NOTHING;
INSERT INTO tool_version(tool_name,version,schema_json,description,access,risk,breaking) VALUES
 ('keel.tasks.command','1.0.0','{"type":"object"}','仅个人助理staging的用户文字分析任务创建与回查','WRITE','LOW',false),
 ('keel.tasks.execution','1.0.0','{"type":"object"}','仅当前租约下本助理自身的任务执行资料','READ','LOW',false)
 ON CONFLICT DO NOTHING;
-- No grants or enabled scopes are implied by migration. No foreign keys.
