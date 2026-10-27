CREATE TABLE task_cost_policy (
    scope VARCHAR(20) NOT NULL,
    scope_key VARCHAR(200) NOT NULL,
    revision INTEGER NOT NULL,
    config JSONB NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(scope,scope_key,revision)
);
INSERT INTO task_cost_policy(scope,scope_key,revision,config,created_by) VALUES('platform','*',1,
 '{"mode":"observe","maxCostCny":1,"maxCalls":32,"baseCostCny":0.03,"perKInputCny":0.005,"memoryBatchTurns":1,"memoryMaxWaitSeconds":30,"contextDedupe":false,"evidenceDedupe":false}', 'migration');
CREATE TABLE task_cost_scope (
    task_id VARCHAR(35) PRIMARY KEY,
    agent_name VARCHAR(63) NOT NULL,
    env VARCHAR(20) NOT NULL,
    owner_user VARCHAR(200) NOT NULL,
    traffic_group VARCHAR(30) NOT NULL,
    context JSONB NOT NULL,
    trace_id VARCHAR(64) NOT NULL DEFAULT '',
    user_message_id BIGINT,
    allocated_memory BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX task_cost_scope_owner ON task_cost_scope(agent_name,env,owner_user,created_at DESC);
CREATE INDEX task_cost_scope_trace ON task_cost_scope(agent_name,env,trace_id);
ALTER TABLE session_memory_job ADD COLUMN batch_start_id BIGINT;
