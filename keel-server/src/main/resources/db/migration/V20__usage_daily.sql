CREATE TABLE usage_daily (
    day DATE NOT NULL,
    env VARCHAR(16) NOT NULL,
    agent VARCHAR(64) NOT NULL,
    calls BIGINT,
    tokens BIGINT,
    cost_cny NUMERIC(18, 6),
    users BIGINT,
    closed BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (day, env, agent)
);
