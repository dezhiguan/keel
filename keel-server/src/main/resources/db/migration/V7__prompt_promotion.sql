-- staging 提示词生效记录。版本正文在 Langfuse，这里只记哪一版在 staging 生效过、以及回归结果。

CREATE TABLE prompt_promotion (
    id           BIGSERIAL PRIMARY KEY,
    agent_name   VARCHAR(64)  NOT NULL,
    prompt_name  VARCHAR(64)  NOT NULL,
    version      INTEGER      NOT NULL,
    sha256       VARCHAR(80)  NOT NULL,
    promoted_by  VARCHAR(64)  NOT NULL,
    promoted_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    gate_run_id  VARCHAR(64),
    gate_status  VARCHAR(16)  NOT NULL,
    CONSTRAINT ck_prompt_promotion_status CHECK (gate_status IN ('PENDING', 'PASSED', 'FAILED', 'INVALID'))
);
CREATE INDEX ix_prompt_promotion_latest ON prompt_promotion (agent_name, prompt_name, promoted_at DESC);

ALTER TABLE release_record ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'release';
ALTER TABLE release_record ADD CONSTRAINT ck_release_record_kind CHECK (kind IN ('release', 'rollback'));
