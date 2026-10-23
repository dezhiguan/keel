CREATE TABLE debug_chat_request (
    agent_name varchar(64) NOT NULL,
    env varchar(16) NOT NULL,
    owner_user varchar(255) NOT NULL,
    session_id varchar(128) NOT NULL,
    request_id varchar(128) NOT NULL,
    input_digest varchar(64) NOT NULL,
    state varchar(16) NOT NULL,
    terminal_frame text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT now() + interval '7 days',
    PRIMARY KEY (agent_name, env, owner_user, session_id, request_id),
    CHECK (state IN ('RUNNING', 'COMPLETE', 'UNKNOWN'))
);
CREATE INDEX debug_chat_request_expiry ON debug_chat_request(expires_at);
