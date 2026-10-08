CREATE TABLE devflow_holdout_result (
    job_id     VARCHAR(16) PRIMARY KEY REFERENCES devflow_job (job_id),
    passed     BOOLEAN        NOT NULL,
    score      NUMERIC(8, 4)  NOT NULL,
    by_tag     JSONB          NOT NULL,
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now()
);
