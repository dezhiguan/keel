package com.keel.server.integration.litellm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Thin-gateway admin API. Budget is CNY and is not converted. */
public class LiteLlmClient {
    private final String baseUrl;
    private final String masterKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();

    public LiteLlmClient(String baseUrl, String masterKey) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
        this.masterKey = masterKey == null ? "" : masterKey;
    }

    /** Null when the base URL is unset. True only when GET /health returns 2xx. */
    public Boolean healthy() {
        if (baseUrl.isBlank()) {
            return null;
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    public String generate(String alias, List<String> models, List<String> fallback, BigDecimal dailyBudgetCny, boolean allowFallback) {
        var body = Map.of(
                "alias", alias,
                "models", models,
                "fallback", fallback,
                "dailyBudgetCny", dailyBudgetCny,
                "allowFallback", allowFallback);
        var response = send("/admin/v1/keys", body);
        var key = response.path("key").asText("");
        if (key.isBlank()) {
            throw new IllegalStateException("薄网关没有返回虚拟 Key");
        }
        return key;
    }

    /** False only when the gateway answers 404 for this alias. Other failures propagate. */
    public boolean hasAlias(String alias) {
        try {
            get("/admin/v1/keys/" + alias);
            return true;
        } catch (IllegalStateException e) {
            var message = e.getMessage() == null ? "" : e.getMessage();
            if (message.endsWith(" 404")) {
                return false;
            }
            throw e;
        }
    }

    public void block(String alias) {
        send("/admin/v1/keys/" + alias + "/block", Map.of());
    }

    /** True when spend has a row at or after {@code since}. Null when the gateway cannot be read. */
    public Boolean calledSince(String alias, Instant since) {
        if (baseUrl.isBlank() || masterKey.isBlank()) {
            return null;
        }
        try {
            for (int page = 1; page <= 20; page++) {
                var body = get("/admin/v1/spend?alias=" + alias + "&page=" + page + "&size=50");
                var data = body.path("data");
                if (!data.isArray() || data.isEmpty()) {
                    return false;
                }
                var recent = false;
                for (var row : data) {
                    var ts = row.path("ts").asText("");
                    if (!ts.isBlank() && !Instant.parse(ts).isBefore(since)) {
                        recent = true;
                    }
                }
                if (recent) {
                    return true;
                }
                if (data.size() < 50) {
                    return false;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public List<Spend> spendAll() {
        var rows = new ArrayList<Spend>();
        String cursor = null;
        for (int page = 0; page < 500; page++) {
            var path = "/admin/v1/spend?limit=100" + (cursor == null || cursor.isBlank() ? "" : "&cursor=" + java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8));
            var body = get(path);
            var data = body.path("data");
            if (!data.isArray() || data.isEmpty()) {
                if (cursor == null && !body.has("nextCursor")) {
                    return spendByPage();
                }
                break;
            }
            data.forEach(row -> rows.add(spend(row)));
            var next = body.path("nextCursor").asText("");
            if (next.isBlank() || next.equals(cursor)) {
                if (body.has("nextCursor") || cursor != null) {
                    break;
                }
                return spendByPage();
            }
            cursor = next;
        }
        return rows;
    }

    /**
     * Newest-first spend rows at or after {@code since}. Stops once a page contains an older timestamp.
     * Blank or unparseable timestamps stay in the window and do not stop the scan.
     */
    public List<Spend> spendSince(Instant since) {
        var rows = new ArrayList<Spend>();
        String cursor = null;
        var cutoff = since == null ? Instant.EPOCH : since;
        for (int page = 0; page < 500; page++) {
            var path = "/admin/v1/spend?limit=100" + (cursor == null || cursor.isBlank() ? "" : "&cursor=" + java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8));
            var body = get(path);
            var data = body.path("data");
            if (!data.isArray() || data.isEmpty()) {
                if (cursor == null && !body.has("nextCursor")) {
                    return spendByPageSince(cutoff);
                }
                break;
            }
            if (cursor == null && !body.has("nextCursor")) {
                return spendByPageSince(cutoff);
            }
            var older = false;
            for (var row : data) {
                var spend = spend(row);
                if (olderThan(spend.ts(), cutoff)) {
                    older = true;
                } else {
                    rows.add(spend);
                }
            }
            if (older) {
                break;
            }
            var next = body.path("nextCursor").asText("");
            if (next.isBlank() || next.equals(cursor)) {
                break;
            }
            cursor = next;
        }
        return rows;
    }

    /** Older gateways page with page/size and do not return nextCursor. */
    private List<Spend> spendByPage() {
        var rows = new ArrayList<Spend>();
        for (int page = 1; page <= 50; page++) {
            var data = get("/admin/v1/spend?page=" + page + "&size=50").path("data");
            if (!data.isArray() || data.isEmpty()) {
                break;
            }
            data.forEach(row -> rows.add(spend(row)));
            if (data.size() < 50) {
                break;
            }
        }
        return rows;
    }

    private List<Spend> spendByPageSince(Instant since) {
        var rows = new ArrayList<Spend>();
        for (int page = 1; page <= 50; page++) {
            var data = get("/admin/v1/spend?page=" + page + "&size=50").path("data");
            if (!data.isArray() || data.isEmpty()) {
                break;
            }
            var older = false;
            for (var row : data) {
                var spend = spend(row);
                if (olderThan(spend.ts(), since)) {
                    older = true;
                } else {
                    rows.add(spend);
                }
            }
            if (older || data.size() < 50) {
                break;
            }
        }
        return rows;
    }

    private static boolean olderThan(String ts, Instant since) {
        if (ts == null || ts.isBlank() || since == null) {
            return false;
        }
        try {
            return Instant.parse(ts).isBefore(since);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Spend spend(JsonNode row) {
        return new Spend(
                row.path("alias").asText(""),
                row.path("model").asText(""),
                row.path("costCny").isNull() ? null : row.path("costCny").asDouble(),
                row.path("ts").asText(""),
                row.has("latencyMs") && !row.path("latencyMs").isNull() ? row.path("latencyMs").asLong() : null,
                row.path("timedOut").asBoolean(false),
                row.path("requestId").asText(""),
                row.path("inputTokens").isNull() ? null : row.path("inputTokens").asInt(),
                row.path("outputTokens").isNull() ? null : row.path("outputTokens").asInt(),
                row.path("reservedCny").asDouble(),
                row.path("status").asText("SETTLED"),
                row.path("attemptId").asText(""),
                row.path("traceId").asText(""),
                row.path("priceVersion").asText(""),
                row.path("routeVersion").asText(""),
                row.path("cacheTokens").isNull() || row.path("cacheTokens").isMissingNode() ? null : row.path("cacheTokens").asInt());
    }

    public List<VirtualKey> keys() {
        var rows = new ArrayList<VirtualKey>();
        get("/admin/v1/keys").path("data").forEach(row -> rows.add(new VirtualKey(
                row.path("alias").asText(""),
                texts(row.path("models")),
                row.path("dailyBudgetCny").asDouble(),
                row.path("spentCny").asDouble(),
                row.path("reservedCny").asDouble(),
                row.path("blocked").asBoolean(false),
                row.path("version").asInt(1),
                optionalInt(row.path("rpm")),
                optionalInt(row.path("tpm")),
                optionalInt(row.path("concurrency")),
                shares(row.path("shares")))));
        return rows;
    }

    /** Recreates an alias with its current settings. The new plaintext is only returned here. */
    public RotatedKey rotate(String alias) {
        var current = get("/admin/v1/keys/" + alias);
        if (current.path("blocked").asBoolean(false)) {
            throw new IllegalStateException("已封禁的虚拟 Key 不可轮换");
        }
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("alias", alias);
        body.put("models", texts(current.path("models")));
        body.put("fallback", texts(current.path("fallback")));
        body.put("dailyBudgetCny", current.path("dailyBudgetCny").decimalValue());
        body.put("allowFallback", current.path("allowFallback").asBoolean(false));
        if (current.path("rpm").isNumber()) body.put("rpm", current.path("rpm").asInt());
        if (current.path("tpm").isNumber()) body.put("tpm", current.path("tpm").asInt());
        if (current.path("concurrency").isNumber()) body.put("concurrency", current.path("concurrency").asInt());
        var created = send("/admin/v1/keys", body);
        var key = created.path("key").asText("");
        if (key.isBlank()) {
            throw new IllegalStateException("薄网关没有返回虚拟 Key");
        }
        return new RotatedKey(alias, key, created.path("version").asInt(1));
    }

    public List<Model> models() {
        var rows = new ArrayList<Model>();
        get("/admin/v1/models").path("data").forEach(row -> rows.add(new Model(
                row.path("name").asText(""),
                row.path("priceConfigured").asBoolean(false),
                row.path("provider").asText(""),
                decimal(row.path("inputCnyPerToken")),
                decimal(row.path("outputCnyPerToken")),
                row.path("priceVersion").asText(""),
                row.path("cacheCnyPerToken").isNumber() ? row.path("cacheCnyPerToken").asDouble() : null,
                row.path("embedding").asBoolean(false),
                row.path("health").asText(""),
                row.path("drift").asBoolean(false))));
        return rows;
    }

    public JsonNode logicalRouteTable(String operation,Map<String,Object> body) {
        return operation==null ? get("/admin/v1/route-table") : send("/admin/v1/route-table"+(operation.isEmpty()?"":"/"+operation),body);
    }

    public RoutePolicy routePolicy() {
        try {
            var body = get("/admin/v1/route-policy");
            if (!body.has("version")) {
                return null;
            }
            return new RoutePolicy(body.path("version").asInt(), body.path("objective").asText("balanced"));
        } catch (RuntimeException error) {
            return null;
        }
    }

    public RoutePolicy publishRoute(String objective) {
        var body = send("/admin/v1/route-policy", Map.of("objective", objective));
        return new RoutePolicy(body.path("version").asInt(), body.path("objective").asText(objective));
    }

    public RoutePolicy rollbackRoute() {
        var body = send("/admin/v1/route-policy/rollback", Map.of());
        return new RoutePolicy(body.path("version").asInt(), body.path("objective").asText("balanced"));
    }

    public Preview previewRoute(String alias, String model) {
        var request = new java.util.LinkedHashMap<String, Object>();
        request.put("alias", alias);
        request.put("model", model == null ? "" : model);
        var body = send("/admin/v1/route/preview", request);
        return new Preview(body.path("version").asInt(), body.path("objective").asText("balanced"), texts(body.path("models")));
    }

    public List<AlertRule> alertRules() {
        try {
            var rows = new ArrayList<AlertRule>();
            get("/admin/v1/alert-rules").path("data").forEach(row -> rows.add(new AlertRule(
                    row.path("id").asText(""), row.path("name").asText(""), row.path("metric").asText(""),
                    row.path("threshold").asDouble(), row.path("window").asText(""), row.path("groupBy").asText(""))));
            return rows;
        } catch (RuntimeException error) {
            return List.of();
        }
    }

    public AlertRule addAlert(String name, String metric, double threshold, String window, String groupBy) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("metric", metric);
        body.put("threshold", threshold);
        body.put("window", window);
        body.put("groupBy", groupBy);
        var created = send("/admin/v1/alert-rules", body);
        return new AlertRule(created.path("id").asText(""), name, metric, threshold, window, groupBy);
    }

    public void probe(String name) {
        send("/admin/v1/models/" + name + "/probe", Map.of());
    }

    public List<LogicalRoute> logicalRoutes() {
        var rows = new ArrayList<LogicalRoute>();
        get("/admin/v1/logical-routes").path("data").forEach(row -> rows.add(new LogicalRoute(
                row.path("logical").asText(""),
                row.path("condition").asText(""),
                row.path("env").asText(""),
                row.path("primary").asText(""),
                row.path("secondary").asText(""),
                row.path("primaryWeight").asInt(0),
                row.path("fallback").asText(""),
                row.path("gray").asText("—"))));
        return rows;
    }

    public List<Channel> channels() {
        var rows = new ArrayList<Channel>();
        get("/admin/v1/channels").path("data").forEach(row -> rows.add(channel(row)));
        return rows;
    }

    public Channel addChannel(String name, String model, String env, int rpm) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("model", model);
        body.put("env", env);
        body.put("rpm", rpm);
        return channel(send("/admin/v1/channels", body));
    }

    public SwitchResult switchChannel(String id) {
        var body = send("/admin/v1/channels/switch", Map.of("id", id == null ? "" : id));
        return new SwitchResult(body.path("moved").asInt(0), body.path("name").asText(""), body.path("id").asText(""));
    }

    public BreakerResult simulateChannel(String id) {
        return breaker(send("/admin/v1/channels/simulate", Map.of("id", id == null ? "" : id)));
    }

    public BreakerResult probeChannel(String id) {
        return breaker(send("/admin/v1/channels/probe", Map.of("id", id == null ? "" : id)));
    }

    private static Channel channel(JsonNode row) {
        return new Channel(
                row.path("id").asText(""),
                row.path("name").asText(""),
                row.path("model").asText(""),
                row.path("env").asText(""),
                row.path("host").asText(""),
                row.path("rpm").isNumber() ? row.path("rpm").asInt() : null,
                row.path("builtin").asBoolean(false),
                row.path("prod").asBoolean(false),
                row.path("breaker").asText("closed"),
                row.path("openUntil").asLong(0),
                row.path("bound").asInt(0));
    }

    private static BreakerResult breaker(JsonNode row) {
        return new BreakerResult(row.path("id").asText(""), row.path("breaker").asText("closed"), row.path("openUntil").asLong(0));
    }

    public void updateBudget(String alias, BigDecimal dailyBudgetCny) {
        updateBudget(alias, dailyBudgetCny, Map.of());
    }

    public void updateBudget(String alias, BigDecimal dailyBudgetCny, Map<String, Integer> limits) {
        updateBudget(alias, dailyBudgetCny, limits, null);
    }

    public void updateBudget(String alias, BigDecimal dailyBudgetCny, Map<String, Integer> limits, List<Share> channels) {
        var body = new java.util.LinkedHashMap<String, Object>();
        if (dailyBudgetCny != null) {
            body.put("dailyBudgetCny", dailyBudgetCny);
        }
        if (limits != null) {
            body.putAll(limits);
        }
        if (channels != null) {
            body.put("channels", channels.stream().map(share -> Map.of("id", share.id(), "rpm", share.rpm())).toList());
        }
        send("/admin/v1/keys/" + alias + "/budget", body);
    }

    public record Share(String id, int rpm) {}

    public record VirtualKey(String alias, List<String> models, double dailyBudgetCny, double spentCny,
                             double reservedCny, boolean blocked, int version, Integer rpm, Integer tpm,
                             Integer concurrency, List<Share> shares) {
        public VirtualKey(String alias, List<String> models, double dailyBudgetCny, double spentCny, boolean blocked) {
            this(alias, models, dailyBudgetCny, spentCny, 0, blocked, 1, null, null, null, List.of());
        }

        public VirtualKey(String alias, List<String> models, double dailyBudgetCny, double spentCny,
                          double reservedCny, boolean blocked, int version) {
            this(alias, models, dailyBudgetCny, spentCny, reservedCny, blocked, version, null, null, null, List.of());
        }

        public VirtualKey(String alias, List<String> models, double dailyBudgetCny, double spentCny,
                          double reservedCny, boolean blocked, int version, Integer rpm, Integer tpm, Integer concurrency) {
            this(alias, models, dailyBudgetCny, spentCny, reservedCny, blocked, version, rpm, tpm, concurrency, List.of());
        }
    }

    public record RotatedKey(String alias, String key, int version) {}

    public record RoutePolicy(int version, String objective) {}

    public record Preview(int version, String objective, List<String> models) {}

    public record AlertRule(String id, String name, String metric, double threshold, String window, String groupBy) {}

    public record Spend(String alias, String model, Double costCny, String ts, Long latencyMs, boolean timedOut,
                        String requestId, Integer inputTokens, Integer outputTokens, double reservedCny,
                        String status, String attemptId, String traceId, String priceVersion, String routeVersion,
                        Integer cacheTokens) {}

    public record LogicalRoute(String logical, String condition, String env, String primary, String secondary,
                               int primaryWeight, String fallback, String gray) {}

    public record Channel(String id, String name, String model, String env, String host, Integer rpm,
                          boolean builtin, boolean prod, String breaker, long openUntil, int bound) {}

    public record SwitchResult(int moved, String name, String id) {}

    public record BreakerResult(String id, String breaker, long openUntil) {}

    public record Model(String name, boolean priceConfigured, String provider,
                        double inputCnyPerToken, double outputCnyPerToken, String priceVersion,
                        Double cacheCnyPerToken, boolean embedding, String health, boolean drift) {}

    private static Integer optionalInt(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asInt();
    }

    private static double decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            try {
                return Double.parseDouble(node.asText(""));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private JsonNode get(String path) {
        if (baseUrl.isBlank() || masterKey.isBlank()) {
            throw new IllegalStateException("薄网关地址或管理密钥未配置");
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + masterKey)
                    .GET()
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("薄网关 " + path + " " + response.statusCode() + detail(response.body()));
            }
            if (response.body() == null || response.body().isBlank()) {
                return json.createObjectNode();
            }
            return json.readTree(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String detail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            var message = new ObjectMapper().readTree(body).path("message").asText("");
            return message.isBlank() ? "" : " " + message;
        } catch (Exception error) {
            return "";
        }
    }

    private static List<Share> shares(JsonNode node) {
        var shares = new ArrayList<Share>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                var id = item.path("id").asText("");
                if (!id.isBlank() && item.path("rpm").canConvertToInt()) {
                    shares.add(new Share(id, item.path("rpm").asInt()));
                }
            });
        }
        return List.copyOf(shares);
    }

    private static List<String> texts(JsonNode node) {
        var values = new ArrayList<String>();
        node.forEach(item -> values.add(item.asText()));
        return List.copyOf(values);
    }

    private JsonNode send(String path, Object body) {
        if (baseUrl.isBlank() || masterKey.isBlank()) {
            throw new IllegalStateException("薄网关地址或管理密钥未配置");
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + masterKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("薄网关 " + path + " " + response.statusCode() + detail(response.body()));
            }
            if (response.body() == null || response.body().isBlank()) {
                return json.createObjectNode();
            }
            return json.readTree(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
