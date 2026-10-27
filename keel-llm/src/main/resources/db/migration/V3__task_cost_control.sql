CREATE TABLE llm_cost_task (
    id VARCHAR(35) PRIMARY KEY,
    alias VARCHAR(100) NOT NULL,
    owner_user VARCHAR(200) NOT NULL,
    traffic_group VARCHAR(30) NOT NULL,
    ticket_hash VARCHAR(64) NOT NULL UNIQUE,
    policy JSONB NOT NULL,
    budget_cny NUMERIC(20,9) NOT NULL CHECK (budget_cny > 0),
    max_calls INTEGER NOT NULL CHECK (max_calls > 0),
    mode VARCHAR(10) NOT NULL CHECK (mode IN ('observe','enforce')),
    state VARCHAR(20) NOT NULL DEFAULT 'running',
    trace_id VARCHAR(64) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);
CREATE INDEX llm_cost_task_scope ON llm_cost_task(alias,owner_user,created_at DESC);
CREATE TABLE llm_cost_request (
    request_id VARCHAR(100) PRIMARY KEY,
    task_id VARCHAR(35) NOT NULL,
    purpose VARCHAR(30) NOT NULL,
    reserved_cny NUMERIC(20,9) NOT NULL CHECK(reserved_cny >= 0),
    reserved_calls INTEGER NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'pending',
    violation BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX llm_cost_request_task ON llm_cost_request(task_id);
CREATE TABLE llm_cost_task_share (
    source_task_id VARCHAR(35) NOT NULL,
    target_task_id VARCHAR(35) NOT NULL,
    weight NUMERIC(20,15) NOT NULL CHECK(weight > 0 AND weight <= 1),
    PRIMARY KEY(source_task_id,target_task_id)
);
CREATE INDEX llm_cost_task_share_target ON llm_cost_task_share(target_task_id);
CREATE TABLE llm_cost_required_alias (
    alias VARCHAR(100) PRIMARY KEY,
    required BOOLEAN NOT NULL
);
