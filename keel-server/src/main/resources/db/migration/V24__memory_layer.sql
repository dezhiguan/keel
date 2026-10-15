ALTER TABLE user_memory ADD COLUMN layer VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE user_memory ADD CONSTRAINT ck_user_memory_layer CHECK (layer IN ('USER', 'TASK', 'EXPERIENCE'));
ALTER TABLE user_memory ADD COLUMN embedding JSONB;
