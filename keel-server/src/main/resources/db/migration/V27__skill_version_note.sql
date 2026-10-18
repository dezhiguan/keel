-- V21 / V22 already ran. Drop the foreign keys they created instead of editing those scripts.
ALTER TABLE skill_version DROP CONSTRAINT IF EXISTS skill_version_skill_name_fkey;
ALTER TABLE agent_skill_grant DROP CONSTRAINT IF EXISTS agent_skill_grant_agent_name_fkey;
ALTER TABLE agent_skill_grant DROP CONSTRAINT IF EXISTS agent_skill_grant_skill_name_fkey;
ALTER TABLE user_memory DROP CONSTRAINT IF EXISTS user_memory_agent_name_fkey;

ALTER TABLE skill_version ADD COLUMN note TEXT NOT NULL DEFAULT '';
ALTER TABLE skill_version ADD COLUMN created_by VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE agent_skill_grant ADD COLUMN upgrade_state VARCHAR(16);
ALTER TABLE agent_skill_grant ADD COLUMN upgrade_run_id VARCHAR(64);
ALTER TABLE agent_skill_grant ADD COLUMN upgrade_target INTEGER;
ALTER TABLE agent_skill_grant ADD COLUMN upgrade_score DOUBLE PRECISION;
ALTER TABLE agent_skill_grant ADD COLUMN upgrade_threshold DOUBLE PRECISION;
ALTER TABLE agent_skill_grant ADD COLUMN upgrade_error TEXT;
ALTER TABLE agent_skill_grant ADD CONSTRAINT ck_agent_skill_grant_upgrade
    CHECK (upgrade_state IS NULL OR upgrade_state IN ('RUNNING', 'PASSED', 'FAILED'));
