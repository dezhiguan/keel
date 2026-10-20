CREATE TABLE agent_employee_read_scope (
  agent_name VARCHAR(64) NOT NULL,
  environment VARCHAR(16) NOT NULL CHECK (environment IN ('dev','staging')),
  target_org VARCHAR(128) NOT NULL,
  enabled BOOLEAN NOT NULL,
  granted_by VARCHAR(128) NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(agent_name,environment,target_org)
);
-- No grants are implied by registration or migration. Console approval is required.
