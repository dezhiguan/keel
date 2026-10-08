package com.keel.server.auth;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

@Component
public class JdbcAssertionReplay implements AssertionReplay {
    private final JdbcTemplate jdbc;

    public JdbcAssertionReplay(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void remember(String jti, Instant expiresAt) {
        jdbc.update("DELETE FROM service_assertion_jti WHERE expires_at <= ?", Timestamp.from(Instant.now()));
        try {
            jdbc.update("INSERT INTO service_assertion_jti (jti, expires_at) VALUES (?, ?)",
                    jti, Timestamp.from(expiresAt));
        } catch (DuplicateKeyException e) {
            throw new KeelException(ErrorCode.AUTH_UNAUTHENTICATED, ErrorCode.AUTH_UNAUTHENTICATED.message());
        }
    }
}
