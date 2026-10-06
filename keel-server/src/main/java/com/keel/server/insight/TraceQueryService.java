package com.keel.server.insight;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.integration.langfuse.LangfuseRateLimit;
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

@Service
public class TraceQueryService {
    private static final Duration LIST_TTL = Duration.ofSeconds(30);
    private static final Duration LIST_WINDOW = Duration.ofDays(7);
    private final LangfuseClient langfuse;
    private final String host;
    private final String projectId;
    private final Map<String, double[]> pricesCnyPerToken;
    private final SavedTraces saved;
    private final Object listCache = new Object();
    private List<JsonNode> cachedRoots = List.of();
    private Instant cachedAt = Instant.EPOCH;

    @Autowired
    public TraceQueryService(LangfuseClient langfuse,
                             @Value("${LANGFUSE_HOST:}") String host,
                             @Value("${LANGFUSE_PROJECT_ID:}") String projectId,
                             SavedTraces saved) {
        this(langfuse, host, projectId, Map.of(), saved);
    }

    public TraceQueryService(LangfuseClient langfuse, String host, String projectId, Map<String, double[]> pricesCnyPerToken) {
        this(langfuse, host, projectId, pricesCnyPerToken, SavedTraces.EMPTY);
    }

    public TraceQueryService(LangfuseClient langfuse, String host, String projectId, Map<String, double[]> pricesCnyPerToken,
                             SavedTraces saved) {
        this.langfuse = langfuse;
        this.host = host == null ? "" : host.replaceAll("/$", "");
        this.projectId = projectId == null ? "" : projectId;
        this.pricesCnyPerToken = pricesCnyPerToken;
        this.saved = saved == null ? SavedTraces.EMPTY : saved;
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
        try {
            var rows = rootObservations();
            var byTrace = new LinkedHashMap<String, List<JsonNode>>();
            for (var row : rows) {
                byTrace.computeIfAbsent(row.path("traceId").asText(""), key -> new ArrayList<>()).add(row);
            }
            if (!byTrace.isEmpty()) {
                var items = new ArrayList<Map<String, Object>>();
                for (var entry : byTrace.entrySet()) {
                    var summary = TraceAssembly.summary(entry.getKey(), entry.getValue(), pricesCnyPerToken);
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
                var matched = items.stream().filter(item -> matches(item, agent, env, status, from, to, multiOnly, minDurationMs)).toList();
                return page(page, size, matched);
            }
        } catch (LangfuseRateLimit limited) {
            throw limited;
        } catch (RuntimeException ignored) {
            // Langfuse 没配好或读失败时，改看本机探针写下的 trace。限流不走这条，否则会把直接上报的调用藏起来。
        }
        return saved.list(page, size, agent, env, status, from, to, multiOnly, minDurationMs);
    }

    /** One Langfuse read of the last 7 days, reused for 30 seconds. Narrower windows are filtered in memory. */
    private List<JsonNode> rootObservations() {
        var now = Instant.now();
        synchronized (listCache) {
            if (!cachedAt.equals(Instant.EPOCH) && cachedAt.plus(LIST_TTL).isAfter(now)) {
                return cachedRoots;
            }
        }
        var body = langfuse.observationsBetween(now.minus(LIST_WINDOW), now);
        var loaded = new ArrayList<JsonNode>();
        body.path("data").forEach(loaded::add);
        synchronized (listCache) {
            cachedRoots = List.copyOf(loaded);
            cachedAt = now;
            return cachedRoots;
        }
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

    private static void copyPending(Map<String, Object> summary, Map<String, Object> pending) {
        for (var key : List.of("pendingReason", "runId", "suspendCount", "humanWaitMs", "userId", "sessionId")) {
            if (pending.get(key) != null) {
                summary.put(key, pending.get(key));
            }
        }
    }

    private static boolean matches(Map<String, Object> item, String agent, String env, String status,
                                   Instant from, Instant to, boolean multiOnly, Integer minDurationMs) {
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
        var detail = TraceAssembly.detail(traceId, rows, url, pricesCnyPerToken);
        if (detail == null) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
        TraceAssembly.attach(detail, saved.context(traceId));
        return detail;
    }
}
