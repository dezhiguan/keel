package com.keel.server.integration.audit;

import com.keel.server.common.R;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Console audit API. Forwards to keel-audit when KEEL_AUDIT_URL is set, otherwise reads the local store. */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditConsoleController {
    private final AuditStore store;
    private final String auditUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public AuditConsoleController(AuditStore store, @Value("${KEEL_AUDIT_URL:}") String auditUrl) {
        this.store = store;
        this.auditUrl = auditUrl == null ? "" : auditUrl.replaceAll("/$", "");
    }

    @GetMapping("/events")
    public Object events(@RequestParam(required = false) String agent,
                         @RequestParam(required = false) String risk,
                         @RequestParam(defaultValue = "all") String env,
                         @RequestParam(required = false) String action,
                         @RequestParam(required = false) String kind,
                         @RequestParam(defaultValue = "1") int page,
                         @RequestParam(defaultValue = "10") int size) {
        if (!auditUrl.isBlank()) {
            return forward("/api/v1/audit/events?agent=" + value(agent) + "&risk=" + value(risk)
                    + "&env=" + value(env) + "&action=" + value(action) + "&kind=" + value(kind)
                    + "&page=" + page + "&size=" + size);
        }
        return R.ok(store.page(agent, risk, env, action, kind, page, size));
    }

    @PostMapping("/verify")
    public Object verify(@RequestParam(required = false) String agent) {
        if (!auditUrl.isBlank()) {
            return forward("/api/v1/audit/verify?agent=" + value(agent));
        }
        return R.ok(store.verify(agent));
    }

    @PostMapping("/exports")
    public ResponseEntity<Map<String, Object>> export() {
        return ResponseEntity.status(202).body(Map.of(
                "code", "OK",
                "traceId", "",
                "data", Map.of("exportId", "pending", "approvalId", "pending")));
    }

    private String forward(String path) {
        try {
            var response = http.send(HttpRequest.newBuilder(URI.create(auditUrl + path))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String value(String raw) {
        return raw == null ? "" : java.net.URLEncoder.encode(raw, java.nio.charset.StandardCharsets.UTF_8);
    }
}
