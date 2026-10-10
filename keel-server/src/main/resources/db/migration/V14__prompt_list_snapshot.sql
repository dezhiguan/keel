-- sync 带上来的版本。列表用它识别「代码里有新版本」，不再翻 Langfuse 历史正文。
CREATE TABLE prompt_code_version (
    agent_name   VARCHAR(64) NOT NULL,
    prompt_name  VARCHAR(64) NOT NULL,
    version      INTEGER     NOT NULL,
    git_sha      VARCHAR(64) NOT NULL,
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (agent_name, prompt_name, version)
);

-- 两个 keel-server 副本共用的标签快照。只放版本号和提交说明，不放提示词正文。
CREATE TABLE prompt_label_snapshot (
    id         SMALLINT PRIMARY KEY,
    payload    JSONB        NOT NULL,
    cached_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_prompt_label_snapshot_one CHECK (id = 1)
);
