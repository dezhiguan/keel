ALTER TABLE eval_run ADD COLUMN runner VARCHAR(32) NOT NULL DEFAULT 'sdk';
ALTER TABLE eval_run ADD COLUMN context_user JSONB;
ALTER TABLE eval_run ADD COLUMN capability_hash VARCHAR(64);
ALTER TABLE eval_run ADD COLUMN progress_result JSONB;
ALTER TABLE eval_run ADD COLUMN case_snapshot JSONB;
CREATE TABLE eval_turn (
    run_id VARCHAR(64) NOT NULL,
    case_id VARCHAR(128) NOT NULL,
    turn_index INTEGER NOT NULL,
    session_group VARCHAR(16) NOT NULL,
    question TEXT NOT NULL,
    answer TEXT,
    evidence JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(run_id,case_id,turn_index)
);
