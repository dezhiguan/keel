-- V21 / V22 already ran. Drop the foreign keys they created instead of editing those scripts.
DO $$
DECLARE
    item record;
BEGIN
    FOR item IN
        SELECT con.conname AS name, rel.relname AS table_name
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE con.contype = 'f'
          AND nsp.nspname = 'public'
          AND rel.relname IN ('skill_version', 'agent_skill_grant', 'user_memory')
    LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', item.table_name, item.name);
    END LOOP;
END $$;

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
