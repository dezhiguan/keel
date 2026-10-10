package com.keel.server.insight;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsoleUsers;
import com.keel.server.common.KeelException;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.integration.langfuse.LangfuseRateLimit;
import com.keel.server.integration.litellm.LiteLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TraceQueryService {
    private static final Logger log = LoggerFactory.getLogger(TraceQueryService.class);
    private static final Duration LIST_TTL = Duration.ofSeconds(30);
    private static final Duration PRICE_TTL = Duration.ofMinutes(5);
    private static final Duration LIST_WINDOW = Duration.ofDays(7);
    private final LangfuseClient langfuse;
    private final String host;
    private final String projectId;
    private final Map<String, double[]> pricesCnyPerToken;
    private final LiteLlmClient pricesSource;
    private final SavedTraces saved;
    private final ConsoleUsers users;
    private final Map<String, CachedRoots> listCache = new ConcurrentHashMap<>();
    private record CachedRoots(List<JsonNode> rows, Instant at) {}
    private final Object priceCache = new Object();
    private Map<String, double[]> cachedPrices = Map.of();
    private Instant cachedPricesAt = Instant.EPOCH;

    @Autowired
    public TraceQueryService(LangfuseClient langfuse,
                             @Value("${LANGFUSE_HOST:}") String host,
                             @Value("${LANGFUSE_PROJECT_ID:}") String projectId,
                             SavedTraces saved,
                             LiteLlmClient pricesSource,
                             ConsoleUsers users) {
        this(langfuse, host, projectId, Map.of(), saved, pricesSource, users);
    }

    public TraceQueryService(LangfuseClient langfuse, String host, String projectId, Map<String, double[]> pricesCnyPerToken) {
        this(langfuse, host, projectId, pricesCnyPerToken, SavedTraces.EMPTY, null);
    }

    public TraceQueryService(LangfuseClient langfuse, String host, String projectId, Map<String, double[]> pricesCnyPerToken,
                             SavedTraces saved) {
        this(langfuse, host, projectId, pricesCnyPerToken, saved, null);
    }

    public TraceQueryService(LangfuseClient langfuse, String host, String projectId, Map<String, double[]> pricesCnyPerToken,
                             SavedTraces saved, LiteLlmClient pricesSource) {
        this(langfuse, host, projectId, pricesCnyPerToken, saved, pricesSource, null);
    }

    public TraceQueryService(LangfuseClient langfuse, String host, String projectId, Map<String, double[]> pricesCnyPerToken,
                             SavedTraces saved, LiteLlmClient pricesSource, ConsoleUsers users) {
        this.langfuse = langfuse;
        this.host = host == null ? "" : host.replaceAll("/$", "");
        this.projectId = projectId == null ? "" : projectId;
        this.pricesCnyPerToken = pricesCnyPerToken == null ? Map.of() : pricesCnyPerToken;
        this.pricesSource = pricesSource;
        this.saved = saved == null ? SavedTraces.EMPTY : saved;
        this.users = users;
    }

    /** Tests pass a price map. Production reads the thin gateway's CNY-per-token catalog. */
    private Map<String, double[]> prices() {
        if (!pricesCnyPerToken.isEmpty()) {
            return pricesCnyPerToken;
        }
        if (pricesSource == null) {
            return Map.of();
        }
        var now = Instant.now();
        synchronized (priceCache) {
            if (!cachedPricesAt.equals(Instant.EPOCH) && cachedPricesAt.plus(PRICE_TTL).isAfter(now)) {
                return cachedPrices;
            }
            var loaded = new LinkedHashMap<String, double[]>();
            try {
                for (var model : pricesSource.models()) {
                    if (model.inputCnyPerToken() > 0 || model.outputCnyPerToken() > 0) {
                        loaded.put(model.name(), new double[] {model.inputCnyPerToken(), model.outputCnyPerToken()});
                    }
                }
            } catch (RuntimeException ignored) {
                // No catalog means the row shows no cost, rather than a zero that looks billed.
            }
            cachedPrices = Map.copyOf(loaded);
            cachedPricesAt = now;
            return cachedPrices;
        }
    }

    public Map<String, Object> list(int page, int size) {
        return list(page, size, "");
    }

    public Map<String, Object> list(int page, int size, String agent) {
        return list(page, size, agent, "all");
    }

    public Map<String, Object> list(int page, int size, String agent, String env) {
        return list(page, size, agent, env, null, null, null, false, null);
    }

    public Map<String, Object> list(int page, int size, String agent, String env, String status,
                                    Instant from, Instant to, boolean multiOnly, Integer minDurationMs) {
        return list(page, size, agent, env, status, from, to, multiOnly, minDurationMs, null);
    }

    public Map<String, Object> list(int page, int size, String agent, String env, String status,
                                    Instant from, Instant to, boolean multiOnly, Integer minDurationMs, String jobId) {
        try {
            var rows = rootObservations(from);
            var byTrace = new LinkedHashMap<String, List<JsonNode>>();
            for (var row : rows) {
                byTrace.computeIfAbsent(row.path("traceId").asText(""), key -> new ArrayList<>()).add(row);
            }
            if (!byTrace.isEmpty()) {
                var items = new ArrayList<Map<String, Object>>();
                for (var entry : byTrace.entrySet()) {
                    var summary = TraceAssembly.summary(entry.getKey(), entry.getValue(), prices());
                    if (summary != null) {
                        items.add(summary);
                    }
                }
                for (var pending : saved.suspended(agent, env)) {
                    var id = String.valueOf(pending.getOrDefault("traceId", ""));
                    var existing = items.stream().filter(item -> id.equals(item.get("traceId"))).findFirst();
                    if (existing.isPresent()) {
                        copyPending(existing.get(), pending);
                    } else {
                        items.add(pending);
                    }
                }
                items.sort(Comparator.comparing((Map<String, Object> item) -> String.valueOf(item.getOrDefault("startedAt", ""))).reversed());
                var matched = items.stream().filter(item -> matches(item, agent, env, status, from, to, multiOnly, minDurationMs, jobId)).toList();
                return present(page(page, size, matched));
            }
        } catch (LangfuseRateLimit limited) {
            throw limited;
        } catch (RuntimeException ex) {
            if (unconfigured(ex)) {
                return present(filterJob(saved.list(page, size, agent, env, status, from, to, multiOnly, minDurationMs), jobId));
            }
            log.warn("Langfuse trace list failed", ex);
            throw new KeelException(ErrorCode.INSIGHT_UPSTREAM_UNAVAILABLE, ErrorCode.INSIGHT_UPSTREAM_UNAVAILABLE.message());
        }
        return present(filterJob(saved.list(page, size, agent, env, status, from, to, multiOnly, minDurationMs), jobId));
    }

    private static boolean unconfigured(Throwable error) {
        var current = error;
        while (current != null) {
            var message = current.getMessage();
            if (message != null && message.contains("未配置")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** Reuse a coarse upstream window, then apply the exact user filters in memory. */
    private List<JsonNode> rootObservations(Instant from) {
        var now = Instant.now();
        var window = from != null && !from.isBefore(now.minus(Duration.ofMinutes(75))) ? Duration.ofHours(2)
                : from != null && !from.isBefore(now.minus(Duration.ofHours(25))) ? Duration.ofHours(26)
                : LIST_WINDOW;
        var key = window.toString();
        return listCache.compute(key, (unused, cached) -> {
            if (cached != null && cached.at().plus(LIST_TTL).isAfter(now)) {
                return cached;
            }
            var body = langfuse.observationsForList(now.minus(window), now);
            var loaded = new ArrayList<JsonNode>();
            body.path("data").forEach(loaded::add);
            return new CachedRoots(List.copyOf(loaded), now);
        }).rows();
    }

    private Map<String, Object> page(int page, int size, List<Map<String, Object>> matched) {
        int from = Math.max(0, (page - 1) * size);
        var items = new ArrayList<Map<String, Object>>();
        for (int i = from; i < Math.min(matched.size(), from + size); i++) {
            items.add(matched.get(i));
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("page", page);
        data.put("size", size);
        data.put("total", matched.size());
        data.put("items", items);
        if (!host.isBlank() && !projectId.isBlank()) {
            data.put("langfuseUrl", host + "/project/" + projectId + "/traces");
        }
        return data;
    }

    private Map<String, Object> present(Map<String, Object> listed) {
        if (users == null || listed == null) {
            return listed;
        }
        var raw = listed.get("items");
        if (raw instanceof List<?> items) {
            for (var item : items) {
                if (item instanceof Map<?, ?> row) {
                    @SuppressWarnings("unchecked")
                    var summary = (Map<String, Object>) row;
                    presentUser(summary);
                }
            }
        }
        return listed;
    }

    /** The span stores an id. The list shows the console user's name, and the role when the user table has one. */
    private void presentUser(Map<String, Object> summary) {
        var raw = summary.get("userId");
        if (!(raw instanceof String id) || id.isBlank()) {
            return;
        }
        var match = users.only().filter(user -> id.equals(user.username())
                || id.equals("local-" + user.username())
                || id.equals(user.displayName())).orElse(null);
        if (match == null) {
            return;
        }
        if (match.displayName() != null && !match.displayName().isBlank()) {
            summary.put("userId", match.displayName());
        }
        if (match.platformRole() != null && !match.platformRole().isBlank()) {
            summary.put("userRole", match.platformRole());
        }
    }

    private static void copyPending(Map<String, Object> summary, Map<String, Object> pending) {
        for (var key : List.of("pendingReason", "runId", "suspendCount", "humanWaitMs", "userId", "sessionId")) {
            if (pending.get(key) != null) {
                summary.put(key, pending.get(key));
            }
        }
    }

    private static Map<String, Object> filterJob(Map<String, Object> listed, String jobId) {
        if (jobId == null || jobId.isBlank() || listed == null) {
            return listed;
        }
        var raw = listed.get("items");
        if (!(raw instanceof List<?> items)) {
            return listed;
        }
        var matched = new ArrayList<Map<String, Object>>();
        for (var item : items) {
            if (item instanceof Map<?, ?> row && jobId.equals(row.get("devflowJobId"))) {
                @SuppressWarnings("unchecked")
                var copy = (Map<String, Object>) row;
                matched.add(copy);
            }
        }
        var data = new LinkedHashMap<String, Object>(listed);
        data.put("items", matched);
        data.put("total", matched.size());
        return data;
    }

    private static boolean matches(Map<String, Object> item, String agent, String env, String status,
                                   Instant from, Instant to, boolean multiOnly, Integer minDurationMs, String jobId) {
        if (jobId != null && !jobId.isBlank() && !jobId.equals(item.get("devflowJobId"))) {
            return false;
        }
        if (agent != null && !agent.isBlank()) {
            var agents = item.get("agents");
            var hit = agent.equals(item.get("rootAgent"))
                    || (agents instanceof List<?> list && list.stream().anyMatch(agent::equals));
            if (!hit) {
                return false;
            }
        }
        if (env != null && !env.isBlank() && !"all".equals(env) && !env.equals(item.get("env"))) {
            return false;
        }
        if (multiOnly && !Boolean.TRUE.equals(item.get("multiAgent"))) {
            return false;
        }
        if (minDurationMs != null && minDurationMs > 0) {
            var duration = item.get("durationMs");
            if (!(duration instanceof Number number) || number.intValue() < minDurationMs) {
                return false;
            }
        }
        if (!inWindow(item.get("startedAt"), from, to)) {
            return false;
        }
        return statusMatches(item, status);
    }

    private static boolean inWindow(Object startedAt, Instant from, Instant to) {
        if (from == null && to == null) {
            return true;
        }
        if (!(startedAt instanceof String text) || text.isBlank()) {
            return false;
        }
        try {
            var started = Instant.parse(text);
            if (from != null && started.isBefore(from)) {
                return false;
            }
            return to == null || started.isBefore(to);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean statusMatches(Map<String, Object> item, String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        var pending = item.get("pendingReason") instanceof String text && !text.isBlank();
        var blocked = Boolean.TRUE.equals(item.get("blocked"));
        return switch (status) {
            case "blocked" -> blocked;
            case "pending" -> pending;
            case "ok", "fallback", "failed" -> status.equals(item.get("status")) && !blocked && !pending;
            default -> false;
        };
    }

    public Map<String, Object> detail(String traceId) {
        try {
            return remoteDetail(traceId);
        } catch (LangfuseRateLimit limited) {
            throw limited;
        } catch (RuntimeException e) {
            var local = saved.detail(traceId);
            if (local != null) {
                return local;
            }
            throw e;
        }
    }

    private Map<String, Object> remoteDetail(String traceId) {
        JsonNode body = langfuse.observationsByTrace(traceId);
        var rows = new ArrayList<JsonNode>();
        body.path("data").forEach(rows::add);
        if (rows.isEmpty()) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
        var url = host.isBlank() || projectId.isBlank() ? "" : host + "/project/" + projectId + "/traces/" + traceId;
        var detail = TraceAssembly.detail(traceId, rows, url, prices());
        if (detail == null) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
        TraceAssembly.attach(detail, saved.context(traceId));
        return detail;
    }
}
