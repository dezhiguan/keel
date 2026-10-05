package com.keel.llm;

import com.keel.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class LlmController {
    private final Gateway gateway;
    private final ModelCatalog catalog;
    private final String adminKey;

    public LlmController(Gateway gateway, ModelCatalog catalog, LlmConfiguration.LlmProperties properties) {
        this.gateway = gateway;
        this.catalog = catalog;
        this.adminKey = properties.getAdminKey() == null ? "" : properties.getAdminKey();
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "up");
    }

    @PostMapping("/admin/v1/keys")
    ResponseEntity<Map<String, Object>> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @RequestBody Map<String, Object> body) {
        admin(authorization);
        var created = gateway.create(
                text(body.get("alias")),
                strings(body.get("models")),
                strings(body.get("fallback")),
                body.get("dailyBudgetCny") == null ? null : new BigDecimal(body.get("dailyBudgetCny").toString()),
                Boolean.TRUE.equals(body.get("allowFallback")) || "true".equals(String.valueOf(body.get("allowFallback"))));
        return ResponseEntity.status(201).body(Map.of("alias", created.alias(), "key", created.key()));
    }

    @PostMapping("/admin/v1/keys/{alias}/block")
    ResponseEntity<Void> block(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @PathVariable String alias) {
        admin(authorization);
        gateway.block(alias);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/v1/keys")
    Map<String, Object> keys(@RequestHeader(value = "Authorization", required = false) String authorization) {
        admin(authorization);
        var rows = gateway.listed().stream().map(key -> {
            var view = new LinkedHashMap<String, Object>();
            view.put("alias", key.alias());
            view.put("models", key.models());
            view.put("dailyBudgetCny", key.dailyBudgetCny());
            view.put("spentCny", key.spentCny());
            view.put("blocked", key.blocked());
            return view;
        }).toList();
        return Map.of("data", rows);
    }

    @GetMapping("/admin/v1/keys/{alias}")
    Map<String, Object> key(@RequestHeader(value = "Authorization", required = false) String authorization,
                            @PathVariable String alias) {
        admin(authorization);
        var key = gateway.requireAlias(alias);
        var view = new LinkedHashMap<String, Object>();
        view.put("alias", key.alias());
        view.put("models", key.models());
        view.put("fallback", key.fallback());
        view.put("dailyBudgetCny", key.dailyBudgetCny());
        view.put("allowFallback", key.allowFallback());
        view.put("blocked", key.blocked());
        return view;
    }

    @GetMapping("/admin/v1/spend")
    Map<String, Object> spend(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @RequestParam(required = false) String alias,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "50") int size) {
        admin(authorization);
        var rows = gateway.spend(alias, page, size).stream().map(row -> Map.of(
                "alias", row.alias(),
                "model", row.model(),
                "inputTokens", row.inputTokens(),
                "outputTokens", row.outputTokens(),
                "costCny", row.costCny(),
                "requestId", row.requestId(),
                "ts", row.ts().toString())).toList();
        return Map.of("data", rows, "page", page, "size", size);
    }

    @GetMapping("/admin/v1/models")
    Map<String, Object> models(@RequestHeader(value = "Authorization", required = false) String authorization) {
        admin(authorization);
        var rows = catalog.models().values().stream().map(model -> Map.of(
                "name", model.name(),
                "inputCnyPerToken", model.inputCnyPerToken(),
                "outputCnyPerToken", model.outputCnyPerToken(),
                "priceConfigured", model.priceConfigured())).toList();
        return Map.of("data", rows);
    }

    @PostMapping({"/v1/chat/completions", "/v1/embeddings"})
    ResponseEntity<String> complete(HttpServletRequest request,
                                    @RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestBody String body) {
        var completion = gateway.complete(authorization, request.getRequestURI(), body, Duration.ofSeconds(45));
        return ResponseEntity.status(completion.status()).body(completion.body());
    }

    @ExceptionHandler(Gateway.GatewayException.class)
    ResponseEntity<Map<String, Object>> gatewayError(Gateway.GatewayException error, HttpServletRequest request) {
        var code = error.code();
        return ResponseEntity.status(code.http()).body(Map.of(
                "code", code.name(),
                "message", error.getMessage(),
                "trace_id", trace(request),
                "retryable", code.retryable()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> startup(IllegalStateException error, HttpServletRequest request) {
        var code = ErrorCode.SERVER_INTERNAL_ERROR;
        return ResponseEntity.status(code.http()).body(Map.of(
                "code", code.name(),
                "message", error.getMessage(),
                "trace_id", trace(request),
                "retryable", code.retryable()));
    }

    private void admin(String authorization) {
        var expected = "Bearer " + adminKey;
        if (adminKey.isBlank() || authorization == null || !authorization.equals(expected)) {
            throw new Gateway.GatewayException(ErrorCode.LLM_KEY_BLOCKED, ErrorCode.LLM_KEY_BLOCKED.message());
        }
    }

    private static String trace(HttpServletRequest request) {
        var header = request.getHeader("X-Trace-Id");
        return header == null || header.isBlank() ? "" : header;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
