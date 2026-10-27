-- Verified historical settlements whose per-attempt details were truncated in Redis.
CREATE TABLE llm_daily_recovery (
    day DATE NOT NULL,
    alias TEXT NOT NULL,
    settled_cny NUMERIC(18, 6) NOT NULL CHECK (settled_cny >= 0),
    source TEXT NOT NULL,
    evidence_sha256 TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (day, alias)
);

-- Recovery records are daily accounting adjustments, never fabricated model attempts.
CREATE VIEW llm_spend_history AS
SELECT * FROM llm_spend
UNION ALL
SELECT 'recovered-day:' || r.alias || ':' || r.day::text AS attempt_id,
       '' AS request_id, r.alias, '' AS model, 'SETTLED' AS status,
       NULL::integer AS input_tokens, NULL::integer AS output_tokens, NULL::integer AS cache_tokens,
       r.settled_cny - COALESCE(b.cost_cny, 0) AS cost_cny,
       0::numeric AS reserved_cny, 'recovered-daily-settlement' AS price_version,
       'daily-recovery' AS route_version, '' AS trace_id, 0::bigint AS latency_ms, FALSE AS timed_out,
       r.day::timestamp AT TIME ZONE 'Asia/Shanghai' AS created_at
FROM llm_daily_recovery r
LEFT JOIN (
    SELECT alias, (created_at AT TIME ZONE 'Asia/Shanghai')::date AS day, SUM(cost_cny) AS cost_cny
    FROM llm_spend WHERE status='SETTLED' GROUP BY alias, (created_at AT TIME ZONE 'Asia/Shanghai')::date
) b ON b.alias=r.alias AND b.day=r.day
WHERE r.settled_cny > COALESCE(b.cost_cny, 0);
