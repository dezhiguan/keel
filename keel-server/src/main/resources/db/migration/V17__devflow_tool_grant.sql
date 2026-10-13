-- H3 is approved before a candidate agent exists, so agent_tool_grant cannot hold it yet.
-- Registration copies only these human-approved grants to agent_tool_grant.
CREATE TABLE devflow_tool_grant (
    job_id        VARCHAR(16)  NOT NULL REFERENCES devflow_job (job_id),
    tool_name     VARCHAR(128) NOT NULL REFERENCES tool (name),
    version_range VARCHAR(128) NOT NULL,
    granted_by    VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, tool_name)
);
