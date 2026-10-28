CREATE TABLE eval_run_participant (
    run_id VARCHAR(64) NOT NULL,
    participant_index INTEGER NOT NULL CHECK (participant_index IN (0,1)),
    user_id VARCHAR(128) NOT NULL,
    principal JSONB NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    authorization_id VARCHAR(64) NOT NULL,
    authorized_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id, participant_index),
    UNIQUE (run_id,user_id),
    UNIQUE (session_id),
    UNIQUE (authorization_id)
);
