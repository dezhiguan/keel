-- 审批中心要展示风险、策略名和截止时间。这些列在 V2 建表时还没有。

ALTER TABLE approval_request
    ADD COLUMN risk VARCHAR(16) NOT NULL DEFAULT 'HIGH',
    ADD COLUMN policy_id BIGINT REFERENCES approval_policy (id),
    ADD COLUMN expires_at TIMESTAMPTZ;

ALTER TABLE approval_request
    ADD CONSTRAINT ck_approval_request_risk CHECK (risk IN ('LOW', 'MID', 'HIGH'));

ALTER TABLE agent_run
    ADD COLUMN prompt TEXT,
    ADD COLUMN resume_token VARCHAR(128);
