ALTER TABLE assistant_analysis_task DROP CONSTRAINT assistant_analysis_task_state_check;
ALTER TABLE assistant_analysis_task ADD CONSTRAINT assistant_analysis_task_state_check CHECK(state IN (
 'waiting_input','queued','running','waiting_authorization','validating','succeeded','failed','cancel_requested','cancelled','unknown'));
CREATE TABLE assistant_analysis_command (
 owner_user text NOT NULL, namespace text NOT NULL, request_id text NOT NULL,
 operation text NOT NULL CHECK(operation IN ('create','prepare','supply','cancel','retry')),
 input_digest text NOT NULL, task_id text NOT NULL, authorization_ref text NOT NULL,
 trace_id text, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(owner_user,namespace,request_id)
);
CREATE INDEX assistant_analysis_command_task ON assistant_analysis_command(task_id,created_at);
-- References are plain columns. No credentials, foreign keys or automatic grants.
