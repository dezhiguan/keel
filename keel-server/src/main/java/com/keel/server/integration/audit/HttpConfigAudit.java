package com.keel.server.integration.audit;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
public class HttpConfigAudit implements ConfigAudit {
    private final AuditStore store;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public HttpConfigAudit(AuditStore store) {
        this.store = store;
    }

    @Override
    public void record(String agent, String env) {
        var base = System.getenv("KEEL_AUDIT_URL");
        if (base == null || base.isBlank()) {
            store.append(agent, env, "config.change", "high", "allowed", agent, null);
            return;
        }
        var body = """
                {"event_id":"%s","ts":"%s","agent":"%s","env":"%s","action":"config.change","risk":"high","decision":"allowed","hash":"%s"}
                """.formatted(UUID.randomUUID(), Instant.now().toString(), agent, env, "0".repeat(64)).replace("\n", "");
        try {
            var response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/audit/events"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                throw new KeelException(ErrorCode.AUDIT_WRITE_FAILED, ErrorCode.AUDIT_WRITE_FAILED.message());
            }
        } catch (KeelException e) {
            throw e;
        } catch (Exception e) {
            throw new KeelException(ErrorCode.AUDIT_WRITE_FAILED, ErrorCode.AUDIT_WRITE_FAILED.message());
        }
    }
}
