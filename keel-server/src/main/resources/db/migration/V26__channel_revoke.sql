ALTER TABLE channel DROP CONSTRAINT ck_channel_status;
ALTER TABLE channel ADD CONSTRAINT ck_channel_status CHECK (status IN ('ONLINE', 'REGISTERED', 'REVOKED'));
