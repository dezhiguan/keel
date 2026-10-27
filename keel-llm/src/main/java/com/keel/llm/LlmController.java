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

    @PostMapping("/admin/v1/task-costs/{operation}")
    Map<String,Object> taskCosts(@RequestHeader(value="Authorization",required=false) String authorization,
                                @PathVariable String operation,@RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        admin(authorization);
        if(gateway.taskCosts==null)throw new IllegalStateException("Task costs require the persistent gateway ledger");
        return switch(operation) {
            case "open" -> gateway.taskCosts.open(body);
            case "inspect" -> gateway.taskCosts.inspect(body.path("taskId").asText());
            case "finish" -> {gateway.taskCosts.finish(body.path("taskId").asText(),body.path("state").asText(),body.path("traceId").asText(""));yield Map.of("accepted",true);}
            case "required" -> {gateway.taskCosts.required(body.path("alias").asText(),body.path("required").asBoolean());yield Map.of("accepted",true);}
            default -> throw new Gateway.GatewayException(ErrorCode.SERVER_INVALID_PARAM,"未知任务成本操作");
        };
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
                Boolean.TRUE.equals(body.get("allowFallback")) || "true".equals(String.valueOf(body.get("allowFallback"))),
                integer(body.get("rpm")),
                integer(body.get("tpm")),
                integer(body.get("concurrency")));
        return ResponseEntity.status(201).body(Map.of("alias", created.alias(), "key", created.key(), "version", created.version()));
    }

    @PostMapping("/admin/v1/keys/{alias}/block")
    ResponseEntity<Void> block(@RequestHeader(value = "Authorization", required = false) String authorization,
                               @PathVariable String alias) {
        admin(authorization);
        gateway.block(alias);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/v1/keys/{alias}/budget")
    ResponseEntity<Void> budget(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @PathVariable String alias,
                                @RequestBody Map<String, Object> body) {
        admin(authorization);
        var raw = body.get("dailyBudgetCny");
        var limits = new LinkedHashMap<String, Integer>();
        try {
            for (var field : List.of("rpm", "tpm", "concurrency")) {
                if (body.containsKey(field)) limits.put(field, body.get(field) == null ? null : new BigDecimal(body.get(field).toString()).intValueExact());
            }
            if (body.containsKey("channels")) {
                gateway.updateChannels(alias, shares(body.get("channels")), limits);
                if (raw != null) {
                    gateway.updateBudget(alias, new BigDecimal(raw.toString()), Map.of());
                }
            } else {
                gateway.updateBudget(alias, raw == null ? null : new BigDecimal(raw.toString()), limits);
            }
        } catch (NumberFormatException | ArithmeticException error) {
            throw new Gateway.GatewayException(ErrorCode.SERVER_INVALID_PARAM, "预算须为数值，RPM、TPM、并发和渠道份额须为正整数或 null");
        }
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
            view.put("reservedCny", key.reservedCny());
            view.put("blocked", key.blocked());
            view.put("version", key.version());
            view.put("rpm", key.rpm());
            view.put("tpm", key.tpm());
            view.put("concurrency", key.concurrency());
            view.put("shares", shareView(key.shares()));
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
        view.put("version", key.version());
        view.put("rpm", key.rpm());
        view.put("tpm", key.tpm());
        view.put("concurrency", key.concurrency());
        view.put("shares", shareView(key.shares()));
        return view;
    }

    @GetMapping("/admin/v1/spend")
    Map<String, Object> spend(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @RequestParam(required = false) String alias,
                              @RequestParam(required = false) String cursor,
                              @RequestParam(required = false) Integer limit,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "50") int size) {
        admin(authorization);
        if (cursor != null || limit != null) {
            var result = gateway.history(alias, cursor, limit == null ? 50 : limit);
            var body = new LinkedHashMap<String, Object>();
            body.put("data", result.rows().stream().map(LlmController::spendView).toList());
            body.put("nextCursor", result.nextCursor());
            return body;
        }
        var rows = gateway.spend(alias, page, size).stream().map(LlmController::spendView).toList();
        return Map.of("data", rows, "page", page, "size", size);
    }

    @GetMapping("/admin/v1/logical-routes")
    Map<String, Object> logicalRoutes(@RequestHeader(value = "Authorization", required = false) String authorization) {
        admin(authorization);
        return Map.of("data", gateway.logicalRoutingSnapshotRules());
    }

    @GetMapping("/admin/v1/route-table")
    Map<String,Object> routeTable(@RequestHeader(value="Authorization",required=false) String authorization) {
        admin(authorization);return gateway.logicalRouting.view();
    }

    @PostMapping({"/admin/v1/route-table", "/admin/v1/route-table/{operation}"})
    Map<String,Object> routeTableWrite(@RequestHeader(value="Authorization",required=false) String authorization,
                                      @PathVariable(required=false) String operation,@RequestBody Map<String,Object> body) {
        admin(authorization);
        long revision;
        try { revision=new java.math.BigDecimal(String.valueOf(body.get("revision"))).longValueExact(); }
        catch(Exception e) {throw RouteTable.invalid("必须传入当前revision");}
        try {
            return switch(operation==null?"save":operation) {
                case "save" -> {
                    if(!(body.get("rules") instanceof List<?>))throw RouteTable.invalid("必须提供完整规则列表");
                    var rules=new com.fasterxml.jackson.databind.ObjectMapper().disable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).convertValue(body.get("rules"),new com.fasterxml.jackson.core.type.TypeReference<List<RouteTable.Rule>>(){});
                    yield gateway.logicalRouting.save(revision,rules);
                }
                case "publish" -> gateway.logicalRouting.publish(revision);
                case "rollback" -> gateway.logicalRouting.rollback(revision);
                case "grants" -> gateway.logicalRouting.grant(revision);
                case "replay" -> gateway.logicalRouting.replay(revision,gateway.spend(null,1,5000));
                default -> throw RouteTable.invalid("未知路由操作");
            };
        } catch(IllegalArgumentException e) {throw RouteTable.invalid("规则字段格式不正确");}
    }

    @GetMapping("/admin/v1/route-policy")
    Map<String, Object> routePolicy(@RequestHeader(value = "Authorization", required = false) String authorization) {
        admin(authorization);
        var current = gateway.routePolicy();
        return Map.of("version", current.version(), "objective", current.objective());
    }

    @PostMapping("/admin/v1/route-policy")
    Map<String, Object> publishRoute(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestBody Map<String, Object> body) {
        admin(authorization);
        var current = gateway.publishRoute(text(body.get("objective")));
        return Map.of("version", current.version(), "objective", current.objective());
    }

    @PostMapping("/admin/v1/route-policy/rollback")
    Map<String, Object> rollbackRoute(@RequestHeader(value = "Authorization", required = false) String authorization) {
        admin(authorization);
        var current = gateway.rollbackRoute();
        return Map.of("version", current.version(), "objective", current.objective());
    }

    @PostMapping("/admin/v1/route/diagnose")
    Map<String,Object> diagnose(@RequestHeader(value="Authorization",required=false) String authorization,
                                @RequestBody Map<String,Object> body) {
        admin(authorization);
        return gateway.diagnose(text(body.get("alias")),text(body.get("model")));
    }

    @PostMapping("/admin/v1/route/preview")
    Map<String, Object> preview(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @RequestBody Map<String, Object> body) {
        admin(authorization);
        var preview = gateway.preview(text(body.get("alias")), text(body.get("model")));
        return Map.of("version", preview.version(), "objective", preview.objective(), "models", preview.models());
    }

    @GetMapping("/admin/v1/alert-rules")
    Map<String, Object> alertRules(@RequestHeader(value = "Authorization", required = false) String authorization) {
        admin(authorization);
        return Map.of("data", gateway.alertRules().stream().map(rule -> Map.of(
                "id", rule.id(), "name", rule.name(), "metric", rule.metric(),
                "threshold", rule.threshold(), "window", rule.window(), "groupBy", rule.groupBy())).toList());
    }

    @PostMapping("/admin/v1/alert-rules")
    Map<String, Object> addAlert(@RequestHeader(value = "Authorization", required = false) String authorization,
                                 @RequestBody Map<String, Object> body) {
        admin(authorization);
        var threshold = body.get("threshold") == null ? -1d : Double.parseDouble(body.get("threshold").toString());
        var rule = gateway.addAlert(text(body.get("name")), text(body.get("metric")), threshold, text(body.get("window")), text(body.get("groupBy")));
        return Map.of("id", rule.id(), "name", rule.name(), "metric", rule.metric());
    }

    @PostMapping("/admin/v1/models/{name}/probe")
    Map<String, String> probe(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @PathVariable String name) {
        admin(authorization);
        gateway.probe(name);
        return Map.of("name", name, "health", "PROBED");
    }

    @GetMapping("/admin/v1/channels")
    Map<String, Object> channels(@RequestHeader(value = "Authorization", required = false) String authorization) {
        admin(authorization);
        return Map.of("data", gateway.channelRows().stream().map(LlmController::channelView).toList());
    }

    @PostMapping("/admin/v1/channels")
    Map<String, Object> addChannel(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @RequestBody Map<String, Object> body) {
        admin(authorization);
        if (body.containsKey("upstreamKey") || body.containsKey("apiKey") || body.containsKey("secret")) {
            throw new Gateway.GatewayException(ErrorCode.SERVER_INVALID_PARAM, "厂商密钥只放在 Secret keel-llm-vendors，接口不接收");
        }
        var rpm = integer(body.get("rpm"));
        return channelView(gateway.addChannel(text(body.get("name")), text(body.get("model")), text(body.get("env")), rpm == null ? 0 : rpm));
    }

    @PostMapping("/admin/v1/channels/switch")
    Map<String, Object> switchChannel(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody Map<String, Object> body) {
        admin(authorization);
        var moved = gateway.switchProd(text(body.get("id")));
        return Map.of("moved", moved.moved(), "name", moved.name(), "id", moved.id());
    }

    @PostMapping("/admin/v1/channels/simulate")
    Map<String, Object> simulateChannel(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody Map<String, Object> body) {
        admin(authorization);
        return breakerView(gateway.simulateChannel(text(body.get("id"))));
    }

    @PostMapping("/admin/v1/channels/probe")
    Map<String, Object> probeChannel(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestBody Map<String, Object> body) {
        admin(authorization);
        return breakerView(gateway.probeChannel(text(body.get("id"))));
    }

    @GetMapping("/admin/v1/models")
    Map<String, Object> models(@RequestHeader(value = "Authorization", required = false) String authorization) {
        admin(authorization);
        var rows = gateway.reports().stream().map(report -> {
            var model = report.model();
            var view = new LinkedHashMap<String, Object>();
            view.put("name", model.name());
            view.put("inputCnyPerToken", model.inputCnyPerToken());
            view.put("outputCnyPerToken", model.outputCnyPerToken());
            view.put("cacheCnyPerToken", model.cacheReadCnyPerToken());
            view.put("priceVersion", model.version());
            view.put("priceConfigured", model.priceConfigured());
            view.put("embedding", model.embedding());
            view.put("health", report.health());
            view.put("drift", report.drift());
            view.put("provider", ModelCatalog.provider(model.upstream()));
            return view;
        }).toList();
        return Map.of("data", rows);
    }

    @PostMapping({"/v1/chat/completions", "/v1/embeddings"})
    void complete(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response,
                  @RequestHeader(value = "Authorization", required = false) String authorization,
                  @RequestBody String body) throws java.io.IOException {
        var requestId = Gateway.newRequestId();
        response.setHeader("X-Request-Id", requestId);
        String ticket="";
        try {ticket=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("metadata").path("keel_cost_ticket").asText("");}
        catch(Exception invalid){throw new Gateway.GatewayException(ErrorCode.SERVER_INVALID_PARAM,"无效模型请求");}
        if(gateway.taskCosts!=null)gateway.taskCosts.checkContext(gateway.costAlias(authorization),ticket);
        if(!ticket.isBlank()) {
            if(gateway.taskCosts==null)throw new IllegalStateException("Task costs require persistence");
            var quote=gateway.taskQuote(authorization,body,request.getRequestURI());
            var node=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            gateway.taskCosts.admit(quote.get("alias").toString(),ticket,requestId,
                    node.path("metadata").path("keel_cost_purpose").asText("interactive"),
                    (BigDecimal)quote.get("reservedCny"),((Number)quote.get("attempts")).intValue());
        }
        try {
        if (streamRequested(body)) {
            response.setStatus(200);
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("X-Accel-Buffering", "no");
            gateway.stream(authorization, request.getRequestURI(), body, Duration.ofMinutes(5), response.getOutputStream(), trace(request), requestId);
            return;
        }
        var chat = request.getRequestURI() != null && request.getRequestURI().endsWith("/chat/completions");
        var completion = gateway.complete(authorization, request.getRequestURI(), body,
                chat ? Duration.ofMinutes(3) : Duration.ofSeconds(45), trace(request), requestId);
        response.setStatus(completion.status());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        if (completion.body() != null) {
            response.getWriter().write(completion.body());
        }
        } finally {
            if(!ticket.isBlank())gateway.taskCosts.reconcileRequest(requestId);
        }
    }

    @ExceptionHandler(Gateway.GatewayException.class)
    ResponseEntity<Map<String, Object>> gatewayError(Gateway.GatewayException error, HttpServletRequest request) {
        var code = error.code();
        return ResponseEntity.status(code.http()).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of(
                "code", code.name(),
                "message", error.getMessage(),
                "trace_id", trace(request),
                "retryable", code.retryable()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> startup(IllegalStateException error, HttpServletRequest request) {
        var code = ErrorCode.SERVER_INTERNAL_ERROR;
        return ResponseEntity.status(code.http()).contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of(
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

    private static List<Map<String, Object>> shareView(List<Gateway.Share> shares) {
        if (shares == null || shares.isEmpty()) {
            return List.of();
        }
        return shares.stream().map(share -> Map.<String, Object>of("id", share.id(), "rpm", share.rpm())).toList();
    }

    private static List<Gateway.Share> shares(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new Gateway.GatewayException(ErrorCode.SERVER_INVALID_PARAM, "至少允许一个渠道");
        }
        var shares = new java.util.ArrayList<Gateway.Share>();
        for (var item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new Gateway.GatewayException(ErrorCode.SERVER_INVALID_PARAM, "渠道份额格式不正确");
            }
            var rpm = map.get("rpm");
            shares.add(new Gateway.Share(text(map.get("id")), rpm == null ? 0 : new BigDecimal(rpm.toString()).intValueExact()));
        }
        return shares;
    }

    private static Map<String, Object> channelView(Gateway.ChannelRow row) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", row.id());
        view.put("name", row.name());
        view.put("model", row.model());
        view.put("env", row.env());
        view.put("host", row.host());
        view.put("rpm", row.rpm());
        view.put("builtin", row.builtin());
        view.put("prod", row.prod());
        view.put("breaker", row.breaker());
        view.put("openUntil", row.openUntil());
        view.put("bound", row.bound());
        return view;
    }

    private static Map<String, Object> breakerView(Gateway.BreakerResult result) {
        return Map.of("id", result.id(), "breaker", result.breaker(), "openUntil", result.openUntil());
    }

    private static Map<String, Object> spendView(Gateway.Spend row) {
        var view = new LinkedHashMap<String, Object>();
        view.put("alias", row.alias());
        view.put("model", row.model());
        view.put("inputTokens", row.inputTokens() < 0 ? null : row.inputTokens());
        view.put("outputTokens", row.outputTokens() < 0 ? null : row.outputTokens());
        view.put("cacheTokens", row.cacheTokens() < 0 ? null : row.cacheTokens());
        view.put("costCny", row.costCny());
        view.put("reservedCny", row.reservedCny());
        view.put("status", row.status());
        view.put("requestId", row.requestId());
        view.put("attemptId", row.attemptId());
        view.put("traceId", row.traceId());
        view.put("priceVersion", row.priceVersion());
        view.put("routeVersion", row.routeVersion());
        view.put("ts", row.ts().toString());
        view.put("latencyMs", row.latencyMs());
        view.put("timedOut", row.timedOut());
        return view;
    }

    private static boolean streamRequested(String body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body == null ? "{}" : body).path("stream").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private static Integer integer(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Integer.valueOf(value.toString());
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
