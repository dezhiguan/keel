CREATE TABLE service_assertion_jti (
    jti        VARCHAR(128) PRIMARY KEY,
    expires_at TIMESTAMPTZ  NOT NULL
);
