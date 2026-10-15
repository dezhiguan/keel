package com.keel.server.metering;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;

public final class JdbcUsageDailyStore implements UsageDailyStore {
    private final JdbcTemplate jdbc;

    public JdbcUsageDailyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void replaceOpen(LocalDate day, String env, UsageMath.Slice slice) {
        jdbc.update("""
                INSERT INTO usage_daily(day, env, agent, calls, tokens, cost_cny, users, closed)
                VALUES (?,?,'*',?,?,?,?,FALSE)
                ON CONFLICT (day, env, agent) DO UPDATE SET
                  calls=EXCLUDED.calls, tokens=EXCLUDED.tokens,
                  cost_cny=EXCLUDED.cost_cny, users=EXCLUDED.users
                WHERE NOT usage_daily.closed
                """, Date.valueOf(day), env, slice.calls(), slice.tokens(), slice.costCny(), slice.users());
    }

    @Override
    public void closeBefore(LocalDate today) {
        jdbc.update("UPDATE usage_daily SET closed=TRUE WHERE day<? AND NOT closed", Date.valueOf(today));
    }

    @Override
    public UsageMath.Slice sumClosed(String env) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(calls),0), COALESCE(SUM(tokens),0), COALESCE(SUM(cost_cny),0)
                FROM usage_daily WHERE env=? AND closed
                """, (rs, row) -> new UsageMath.Slice(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3), null), env);
    }

    @Override
    public UsageMath.Slice open(String env, LocalDate day) {
        var rows = jdbc.query("""
                SELECT calls, tokens, cost_cny FROM usage_daily
                WHERE env=? AND day=? AND NOT closed AND agent='*'
                """, (rs, row) -> new UsageMath.Slice(
                (Long) rs.getObject(1), (Long) rs.getObject(2), rs.getBigDecimal(3), null), env, Date.valueOf(day));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public Long distinctUsers(String env) {
        try {
            if ("all".equals(env)) {
                return jdbc.queryForObject("""
                        SELECT COUNT(DISTINCT actor_user) FROM agent_run
                        WHERE actor_user IS NOT NULL AND actor_user <> ''
                        """, Long.class);
            }
            return jdbc.queryForObject("""
                    SELECT COUNT(DISTINCT actor_user) FROM agent_run
                    WHERE env=? AND actor_user IS NOT NULL AND actor_user <> ''
                    """, Long.class, env);
        } catch (RuntimeException error) {
            return null;
        }
    }

    @Override
    public LocalDate earliest(String env) {
        var rows = jdbc.query("SELECT MIN(day) FROM usage_daily WHERE env=?",
                (rs, row) -> rs.getDate(1) == null ? null : rs.getDate(1).toLocalDate(), env);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
