CREATE TABLE channel (
    id            BIGSERIAL PRIMARY KEY,
    agent_name    VARCHAR(64)  NOT NULL REFERENCES agent (name),
    kind          VARCHAR(16)  NOT NULL,
    identity      VARCHAR(16)  NOT NULL,
    origin        VARCHAR(256),
    quota_per_hour INTEGER,
    status        VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_channel_kind CHECK (kind IN ('HOSTED', 'EMBED', 'WEB')),
    CONSTRAINT ck_channel_identity CHECK (identity IN ('EXCHANGE', 'OPENID', 'ANONYMOUS')),
    CONSTRAINT ck_channel_status CHECK (status IN ('ONLINE', 'REGISTERED'))
);

CREATE TABLE channel_credential (
    id          BIGSERIAL PRIMARY KEY,
    channel_id  BIGINT       NOT NULL REFERENCES channel (id),
    public_id   VARCHAR(64)  NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
