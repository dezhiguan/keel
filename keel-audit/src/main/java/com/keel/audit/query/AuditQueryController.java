package com.keel.audit.query;

import com.keel.audit.chain.ChainVerifyJob;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditQueryController {
    private final AuditLog log;
    private final ChainVerifyJob verifyJob;
    private final ExportService exports;

    public AuditQueryController(AuditLog log, ChainVerifyJob verifyJob, ExportService exports) {
        this.log = log;
        this.verifyJob = verifyJob;
        this.exports = exports;
    }

    @GetMapping("/events")
    public Map<String, Object> events(@RequestParam(required = false) String agent,
                                      @RequestParam(required = false) String action,
                                      @RequestParam(required = false) String risk,
                                      @RequestParam(required = false) String traceId,
                                      @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "10") int size) {
        Instant fromInstant = from == null || from.isBlank() ? null : Instant.parse(from);
        Instant toInstant = to == null || to.isBlank() ? null : Instant.parse(to);
        var items = log.filter(agent, action, risk, traceId, fromInstant, toInstant, page, size);
        var data = new LinkedHashMap<String, Object>();
        data.put("page", page);
        data.put("size", size);
        data.put("total", log.total(agent, action, risk, traceId, fromInstant, toInstant));
        data.put("items", items);
        return envelope(data);
    }

    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestParam(required = false) String agent) {
        long started = System.currentTimeMillis();
        var broken = verifyJob.verify(agent == null ? "" : agent);
        var data = new LinkedHashMap<String, Object>();
        data.put("checked", broken.isPresent() ? 1 : 0);
        data.put("intact", broken.isEmpty());
        data.put("brokenAt", broken.orElse(null));
        data.put("elapsedMs", System.currentTimeMillis() - started);
        return envelope(data);
    }

    @PostMapping("/exports")
    public ResponseEntity<Map<String, Object>> export(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        var filter = (Map<String, Object>) body.getOrDefault("filter", Map.of());
        var created = exports.request(filter);
        return ResponseEntity.status(202).body(envelope(Map.of(
                "exportId", created.exportId(),
                "approvalId", created.approvalId())));
    }

    private static Map<String, Object> envelope(Object data) {
        var body = new LinkedHashMap<String, Object>();
        body.put("code", "OK");
        body.put("traceId", "");
        body.put("data", data);
        return body;
    }
}
