package com.keel.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.common.error.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class Gateway {
    public static final Pattern ALIAS = Pattern.compile("^[a-z][a-z0-9-]{1,38}[a-z0-9]-(dev|test|staging|prod)$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final java.util.concurrent.ScheduledExecutorService STREAM_DEADLINES =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("llm-stream-deadline").factory());

    private final ModelCatalog catalog;
    private final BudgetCounter budget;
    private final Upstream upstream;
    private final KeyDirectory keys;
    private final SpendLog spendLog;
    private final SpendLedger history;
    private final Admission admission;
    private final RoutePolicy routes;
    private final AlertRules alertRules;
    private final ProbeMarks probed;
    private final RequestDefaults requestDefaults;
    private final ChannelBoard channels;
    final LogicalRouting logicalRouting;

    public Gateway(ModelCatalog catalog, BudgetCounter budget, Upstream upstream) {
        this(catalog, budget, upstream, new KeyDirectory());
    }

    public Gateway(ModelCatalog catalog, BudgetCounter budget, Upstream upstream, KeyDirectory keys) {
        this(catalog, budget, upstream, keys, new SpendLog(null));
    }

    public Gateway(ModelCatalog catalog, BudgetCounter budget, Upstream upstream, KeyDirectory keys, SpendLog spendLog) {
        this(catalog, budget, upstream, keys, spendLog, null, new Admission(null));
    }

    public Gateway(ModelCatalog catalog, BudgetCounter budget, Upstream upstream, KeyDirectory keys, SpendLog spendLog,
                   SpendLedger history, Admission admission) {
        this(catalog, budget, upstream, keys, spendLog, history, admission, new ProbeMarks(null));
    }

    public Gateway(ModelCatalog catalog, BudgetCounter budget, Upstream upstream, KeyDirectory keys, SpendLog spendLog,
                   SpendLedger history, Admission admission, ProbeMarks probed) {
        this(catalog, budget, upstream, keys, spendLog, history, admission, probed, new RoutePolicy());
    }

    public Gateway(ModelCatalog catalog, BudgetCounter budget, Upstream upstream, KeyDirectory keys, SpendLog spendLog,
                   SpendLedger history, Admission admission, ProbeMarks probed, RoutePolicy routes) {
        this(catalog, budget, upstream, keys, spendLog, history, admission, probed, routes, new AlertRules(null));
    }

    public Gateway(ModelCatalog catalog, BudgetCounter budget, Upstream upstream, KeyDirectory keys, SpendLog spendLog,
                   SpendLedger history, Admission admission, ProbeMarks probed, RoutePolicy routes, AlertRules alertRules) {
        this(catalog, budget, upstream, keys, spendLog, history, admission, probed, routes, alertRules, new RequestDefaults(""));
    }

    Gateway(ModelCatalog catalog, BudgetCounter budget, Upstream upstream, KeyDirectory keys, SpendLog spendLog,
            SpendLedger history, Admission admission, ProbeMarks probed, RoutePolicy routes, AlertRules alertRules,
            RequestDefaults requestDefaults) {
        this(catalog, budget, upstream, keys, spendLog, history, admission, probed, routes, alertRules,
                requestDefaults, new ChannelBoard(null));
    }

    Gateway(ModelCatalog catalog, BudgetCounter budget, Upstream upstream, KeyDirectory keys, SpendLog spendLog,
            SpendLedger history, Admission admission, ProbeMarks probed, RoutePolicy routes, AlertRules alertRules,
            RequestDefaults requestDefaults, ChannelBoard channels) {
        this.catalog = catalog;
        this.budget = budget;
        this.upstream = upstream;
        this.keys = keys;
        this.spendLog = spendLog;
        this.history = history;
        this.admission = admission == null ? new Admission(null) : admission;
        this.probed = probed == null ? new ProbeMarks(null) : probed;
        this.routes = routes;
        this.alertRules = alertRules;
        this.requestDefaults = requestDefaults;
        this.channels = channels == null ? new ChannelBoard(null) : channels;
        this.logicalRouting = new LogicalRouting(this,catalog,keys,routes.table,routes.journal);
    }

    public synchronized Created create(String alias, List<String> models, List<String> fallback, BigDecimal dailyBudgetCny, boolean allowFallback) {
        return create(alias, models, fallback, dailyBudgetCny, allowFallback, null, null, null);
    }

    public synchronized Created create(String alias, List<String> models, List<String> fallback, BigDecimal dailyBudgetCny,
                                       boolean allowFallback, Integer rpm, Integer tpm, Integer concurrency) {
        if (alias == null || !ALIAS.matcher(alias).matches()) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "别名必须是 {agent}-{env}");
        }
        if (dailyBudgetCny == null || dailyBudgetCny.signum() <= 0) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "dailyBudgetCny 必须大于 0");
        }
        var expanded = new ArrayList<String>();
        for (var model : models) {
            if (!expanded.contains(model)) {
                expanded.add(model);
            }
            for (var concrete : routes.table.targets(model, envOf(alias))) {
                if (catalog.models().containsKey(concrete) && !expanded.contains(concrete)) {
                    expanded.add(concrete);
                }
            }
        }
        expanded.stream().filter(model -> !routes.table.logical(model)).forEach(catalog::require);
        models = expanded;
        limit(rpm);
        limit(tpm);
        limit(concurrency);
        var token = "sk-keel-" + UUID.randomUUID().toString().replace("-", "");
        var existing = keys.byAlias(alias);
        var shares = existing == null || existing.shares() == null ? List.<Share>of() : existing.shares();
        var key = new VirtualKey(alias, token, List.copyOf(models), List.copyOf(fallback), dailyBudgetCny, allowFallback, false, 1,
                rpm, tpm, concurrency, shares);
        var saved = keys.rotate(key);
        return new Created(alias, token, saved.version());
    }

    public synchronized void block(String alias) {
        var key = keys.byAlias(alias);
        if (key == null) {
            throw new GatewayException(ErrorCode.SERVER_NOT_FOUND, "虚拟 Key 不存在");
        }
        keys.save(key.asBlocked());
    }

    public synchronized void updateBudget(String alias, BigDecimal dailyBudgetCny) {
        updateBudget(alias, dailyBudgetCny, Map.of());
    }

    public synchronized void updateBudget(String alias, BigDecimal dailyBudgetCny, Map<String, Integer> limits) {
        if (dailyBudgetCny == null || dailyBudgetCny.signum() <= 0) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "dailyBudgetCny 必须大于 0");
        }
        var key = keys.byAlias(alias);
        if (key == null) {
            throw new GatewayException(ErrorCode.SERVER_NOT_FOUND, "虚拟 Key 不存在");
        }
        limits.values().forEach(Gateway::limit);
        keys.save(new VirtualKey(key.alias(), key.token(), key.models(), key.fallback(), dailyBudgetCny,
                key.allowFallback(), key.blocked(), key.version(),
                limits.containsKey("rpm") ? limits.get("rpm") : key.rpm(),
                limits.containsKey("tpm") ? limits.get("tpm") : key.tpm(),
                limits.containsKey("concurrency") ? limits.get("concurrency") : key.concurrency(),
                key.shares() == null ? List.of() : key.shares()));
    }

    /** Replaces the concrete models this key may call with the selected same-env channels. */
    public synchronized void updateChannels(String alias, List<Share> shares, Map<String, Integer> limits) {
        var key = requireAlias(alias);
        if (shares == null || shares.isEmpty()) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "至少允许一个渠道");
        }
        var rows = channelRows();
        var models = new ArrayList<String>();
        key.models().stream().filter(routes.table::logical).forEach(models::add);
        var clean = new ArrayList<Share>();
        var seen = new java.util.HashSet<String>();
        for (var share : shares) {
            if (share == null || share.id() == null || share.id().isBlank() || share.rpm() < 1) {
                throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "渠道 RPM 须为正整数");
            }
            if (!seen.add(share.id())) {
                throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "渠道不能重复");
            }
            var row = rows.stream().filter(item -> item.id().equals(share.id())).findFirst()
                    .orElseThrow(() -> new GatewayException(ErrorCode.SERVER_NOT_FOUND, "渠道不存在"));
            var env = envOf(alias);
            var listed = java.util.Arrays.stream(row.env().split("、")).filter(item -> !item.isBlank()).toList();
            if (!env.isEmpty() && !listed.contains(env)) {
                throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "只能允许同环境渠道");
            }
            int others = 0;
            for (var peer : keys.list()) {
                if (peer.alias().equals(alias) || peer.shares() == null) {
                    continue;
                }
                for (var existing : peer.shares()) {
                    if (share.id().equals(existing.id())) {
                        others += existing.rpm();
                    }
                }
            }
            if (row.rpm() != null && others + share.rpm() > row.rpm()) {
                throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "渠道份额超出厂商上限");
            }
            if (row.model() != null && !row.model().isBlank() && !models.contains(row.model())) {
                models.add(row.model());
            }
            clean.add(new Share(share.id(), share.rpm()));
        }
        if (limits != null) {
            limits.values().forEach(Gateway::limit);
        }
        var fallback = key.fallback().stream().filter(models::contains).toList();
        var kept = limits == null ? Map.<String, Integer>of() : limits;
        keys.save(new VirtualKey(key.alias(), key.token(), List.copyOf(models), fallback, key.dailyBudgetCny(),
                key.allowFallback(), key.blocked(), key.version(), null,
                kept.containsKey("tpm") ? kept.get("tpm") : key.tpm(),
                kept.containsKey("concurrency") ? kept.get("concurrency") : key.concurrency(),
                List.copyOf(clean)));
    }

    public List<ListedKey> listed() {
        return keys.list().stream()
                .map(key -> {
                    var amounts = budget.snapshot(key.alias());
                    return new ListedKey(key.alias(), key.models(), key.dailyBudgetCny(),
                            amounts.settledCny(), amounts.reservedCny(), key.blocked(), key.version(),
                            key.rpm(), key.tpm(), key.concurrency(),
                            key.shares() == null ? List.of() : key.shares());
                })
                .toList();
    }

    public synchronized VirtualKey requireAlias(String alias) {
        var key = keys.byAlias(alias);
        if (key == null) {
            throw new GatewayException(ErrorCode.SERVER_NOT_FOUND, "虚拟 Key 不存在");
        }
        return key;
    }

    public synchronized void probe(String name) {
        var model = catalog.models().get(name);
        if (model == null) {
            throw new GatewayException(ErrorCode.SERVER_NOT_FOUND, "模型不存在");
        }
        var result = upstream.post(model.upstream(), model.upstreamKey(), "/v1/chat/completions",
                "{\"model\":\"" + name + "\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":1}",
                Duration.ofSeconds(5));
        if (result.status() >= 400 || result.failed()) {
            throw new GatewayException(ErrorCode.SERVER_INTERNAL_ERROR, "探测失败");
        }
        probed.add(name);
    }

    public List<ModelReport> reports() {
        var ledger = history == null ? spendLog : history;
        var settled = new HashMap<String, Spend>();
        for (var row : ledger.latestSettledByModel()) {
            settled.put(row.model(), row);
        }
        return catalog.models().values().stream().map(model -> {
            var last = settled.get(model.name());
            var drift = last != null && last.priceVersion() != null && !last.priceVersion().isBlank()
                    && !model.version().equals(last.priceVersion());
            var health = last != null ? "LIVE" : probed.contains(model.name()) ? "PROBED" : "CONFIGURED";
            return new ModelReport(model, health, drift);
        }).toList();
    }

    public SpendCursor.Page history(String alias, String cursor, int limit) {
        var ledger = history == null ? spendLog : history;
        return ledger.page(alias, cursor, limit);
    }

    public List<Spend> spend(String alias, int page, int size) {
        var matched = spendLog.all().stream()
                .filter(row -> alias == null || alias.isBlank() || row.alias.equals(alias))
                .sorted((left, right) -> {
                    int pending = Boolean.compare("PENDING".equals(right.status()), "PENDING".equals(left.status()));
                    return pending != 0 ? pending : right.ts().compareTo(left.ts());
                }).toList();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(matched.size(), from + size);
        return from >= matched.size() ? List.of() : matched.subList(from, to);
    }

    public Completion complete(String bearer, String path, String body, Duration timeout) {
        return complete(bearer, path, body, timeout, "");
    }

    public Completion complete(String bearer, String path, String body, Duration timeout, String traceId) {
        return complete(bearer, path, body, timeout, traceId, newRequestId());
    }

    public static String newRequestId() {
        return LocalDate.now(BudgetCounter.SHANGHAI) + "@" + UUID.randomUUID();
    }

    public Completion complete(String bearer, String path, String body, Duration timeout, String traceId, String requestId) {
        var key = virtualKey(bearer);
        var request = read(body);
        var named = request.path("model").asText("");
        var row=RouteTable.match(routes.table.current().rules(),named,envOf(key.alias()));
        var requested = row==null?liveModel(named,key.alias()):row.select(requestId);
        if (!key.models.contains(named) && !key.models.contains(requested)) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "模型不在该 Key 的允许列表里");
        }
        var attempts = namedAttempts(key,row,requested,"/v1/embeddings".equals(path),request);
        if(row!=null) routes.journal.record(requestId,key.alias(),named,inputEstimate(request));
        var forwarding = (ObjectNode) request.deepCopy();
        var maxCost = reservationCost(attempts, path);
        try {
            admission.enter(key);
        } catch (GatewayException denied) {
            line(key.alias(), requested, 0, 0, BigDecimal.ZERO, requestId, 0, false,
                    "FAILED", BigDecimal.ZERO, traceId, "rejected:" + denied.code().name(), 0);
            throw denied;
        }
        if (!budget.reserve(key.alias, requestId, maxCost, key.dailyBudgetCny)) {
            admission.leave(key, 0);
            line(key.alias(), requested, 0, 0, BigDecimal.ZERO, requestId, 0, false,
                    "FAILED", BigDecimal.ZERO, traceId, "rejected:" + ErrorCode.LLM_BUDGET_EXCEEDED.name(), 0);
            throw new GatewayException(ErrorCode.LLM_BUDGET_EXCEEDED, ErrorCode.LLM_BUDGET_EXCEEDED.message());
        }
        Upstream.Result result = null;
        String used = requested;
        long latencyMs = 0;
        var billableResponse = false;
        var tokensUsed = 0;
        try {
            for (int i = 0; i < attempts.size(); i++) {
                used = attempts.get(i);
                var model = catalog.require(used);
                if (!routes.allowAttempt(breakerKey(key, used))) {
                    if (i < attempts.size() - 1) continue;
                    throw new GatewayException(ErrorCode.SERVER_INTERNAL_ERROR, "可用模型渠道已熔断");
                }
                countShare(key, used, requestId, traceId, route(i));
                var forwarded = requestDefaults.apply(forwarding, key.alias(), used, path);
                var started = System.nanoTime();
                try {
                    result = upstream.post(model.upstream(), model.upstreamKey(), path, forwarded, timeout);
                } catch (RuntimeException error) {
                    observe(key, used, true, 0);
                    line(key.alias(), used, 0, 0, BigDecimal.ZERO, requestId, 0, true,
                            "FAILED", BigDecimal.ZERO, traceId, route(i), -1);
                    if (i < attempts.size() - 1) continue;
                    throw new GatewayException(ErrorCode.SERVER_INTERNAL_ERROR, "上游失败");
                }
                latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
                if (result.retryable()) {
                    observe(key, used, true, latencyMs);
                    line(key.alias(), used, 0, 0, BigDecimal.ZERO, requestId, latencyMs, result.status() == 504,
                            "FAILED", BigDecimal.ZERO, traceId, route(i), -1);
                    if (i < attempts.size() - 1) {
                        continue;
                    }
                    throw new GatewayException(ErrorCode.SERVER_INTERNAL_ERROR, "上游失败");
                }
                break;
            }
            if (result == null) {
                throw new GatewayException(ErrorCode.SERVER_INTERNAL_ERROR, "上游失败");
            }
            var route = route(attempts.indexOf(used));
            if (result.status() >= 400) {
                observe(key, used, false, latencyMs);
                line(key.alias(), used, 0, 0, BigDecimal.ZERO, requestId, latencyMs, false,
                        "FAILED", BigDecimal.ZERO, traceId, route, -1);
                return new Completion(result.status(), result.body(), null);
            }
            billableResponse = true;
            if (!validUsage(result.body())) {
                budget.markPending(key.alias, requestId);
                return new Completion(result.status(), result.body(), line(key.alias(), used, -1, -1, null, requestId,
                        latencyMs, false, "PENDING", maxCost, traceId, route, -1));
            }
            var node = read(result.body());
            var cost = costOf(used, node);
            budget.settle(key.alias, requestId, cost);
            observe(key, used, false, latencyMs);
            int input = tokens(node, "prompt_tokens");
            int output = tokens(node, "completion_tokens");
            tokensUsed = input + output;
            return new Completion(result.status(), result.body(), line(key.alias(), used, input, output, cost, requestId,
                    latencyMs, false, "SETTLED", BigDecimal.ZERO, traceId, route, cacheTokens(node)));
        } finally {
            if (!billableResponse) budget.release(key.alias, requestId);
            admission.leave(key, tokensUsed);
        }
    }

    public Spend stream(String bearer, String path, String body, Duration timeout, java.io.OutputStream client, String traceId) {
        return stream(bearer, path, body, timeout, client, traceId, newRequestId());
    }

    public Spend stream(String bearer, String path, String body, Duration timeout, java.io.OutputStream client, String traceId, String requestId) {
        var key = virtualKey(bearer);
        var request = read(body);
        var named = request.path("model").asText("");
        var row=RouteTable.match(routes.table.current().rules(),named,envOf(key.alias()));
        var requested = row==null?liveModel(named,key.alias()):row.select(requestId);
        if (!key.models.contains(named) && !key.models.contains(requested)) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "模型不在该 Key 的允许列表里");
        }
        var attempts = namedAttempts(key,row,requested,"/v1/embeddings".equals(path),request);
        if(row!=null) routes.journal.record(requestId,key.alias(),named,inputEstimate(request));
        var forwarding = (ObjectNode) request.deepCopy();
        var options = forwarding.path("stream_options").isObject()
                ? (ObjectNode) forwarding.path("stream_options")
                : forwarding.putObject("stream_options");
        options.put("include_usage", true);
        var maxCost = reservationCost(attempts, path);
        try {
            admission.enter(key);
        } catch (GatewayException denied) {
            line(key.alias(), requested, 0, 0, BigDecimal.ZERO, requestId, 0, false,
                    "FAILED", BigDecimal.ZERO, traceId, "rejected:" + denied.code().name(), 0);
            throw denied;
        }
        if (!budget.reserve(key.alias, requestId, maxCost, key.dailyBudgetCny)) {
            admission.leave(key, 0);
            line(key.alias(), requested, 0, 0, BigDecimal.ZERO, requestId, 0, false,
                    "FAILED", BigDecimal.ZERO, traceId, "rejected:" + ErrorCode.LLM_BUDGET_EXCEEDED.name(), 0);
            throw new GatewayException(ErrorCode.LLM_BUDGET_EXCEEDED, ErrorCode.LLM_BUDGET_EXCEEDED.message());
        }
        var billableResponse = false;
        var tokensUsed = 0;
        try {
            for (int i = 0; i < attempts.size(); i++) {
                var used = attempts.get(i);
                var model = catalog.require(used);
                if (!routes.allowAttempt(breakerKey(key, used))) {
                    if (i < attempts.size() - 1) continue;
                    throw new GatewayException(ErrorCode.SERVER_INTERNAL_ERROR, "可用模型渠道已熔断");
                }
                countShare(key, used, requestId, traceId, route(i));
                var forwarded = requestDefaults.apply(forwarding, key.alias(), used, path);
                var started = System.nanoTime();
                Upstream.Stream opened;
                try {
                    opened = upstream.open(model.upstream(), model.upstreamKey(), path, forwarded, timeout);
                    if (streamRejected(opened) && (forwarding.has("stream_options") || forwarding.has("metadata"))) {
                        opened.close();
                        forwarding.remove("stream_options");
                        forwarding.remove("metadata");
                        forwarded = requestDefaults.apply(forwarding, key.alias(), used, path);
                        opened = upstream.open(model.upstream(), model.upstreamKey(), path, forwarded, timeout);
                    }
                } catch (RuntimeException error) {
                    observe(key, used, true, 0);
                    line(key.alias(), used, 0, 0, BigDecimal.ZERO, requestId, 0, true, "FAILED", BigDecimal.ZERO, traceId, route(i), -1);
                    if (i < attempts.size() - 1) continue;
                    throw new GatewayException(ErrorCode.SERVER_INTERNAL_ERROR, "上游失败");
                }
                long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
                if (opened.retryable()) {
                    var detail = upstreamDetail(opened);
                    opened.close();
                    observe(key, used, true, latencyMs);
                    line(key.alias(), used, 0, 0, BigDecimal.ZERO, requestId, latencyMs, opened.status() == 504,
                            "FAILED", BigDecimal.ZERO, traceId, route(i), -1);
                    if (i < attempts.size() - 1) continue;
                    throw new GatewayException(ErrorCode.SERVER_INTERNAL_ERROR, upstreamFailure(opened.status(), detail));
                }
                if (opened.status() >= 400) {
                    var detail = upstreamDetail(opened);
                    opened.close();
                    observe(key, used, false, latencyMs);
                    line(key.alias(), used, 0, 0, BigDecimal.ZERO, requestId, latencyMs, false, "FAILED", BigDecimal.ZERO, traceId, route(i), -1);
                    throw new GatewayException(ErrorCode.SERVER_INTERNAL_ERROR, upstreamFailure(opened.status(), detail));
                }
                var captured = new StringBuilder();
                var cancelled = false;
                var buffer = new byte[1024];
                // HttpRequest.timeout covers headers, not subsequent InputStream reads.
                // Keep draining after the caller disconnects, but never beyond this attempt's deadline.
                var active = opened;
                var remaining = Math.max(1L, timeout.toMillis() - (System.nanoTime() - started) / 1_000_000L);
                var deadline = STREAM_DEADLINES.schedule(active::close, remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
                try (var input = opened.body()) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        captured.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
                        if (captured.length() > 131072) captured.delete(0, captured.length() - 65536);
                        try {
                            if (cancelled) continue;
                            client.write(buffer, 0, read);
                            client.flush();
                        } catch (java.io.IOException closed) {
                            cancelled = true;
                            // Usage usually arrives in the last chunk. A closed caller
                            // must not prevent the gateway from accounting for it.
                        }
                    }
                } catch (java.io.IOException closed) {
                    cancelled = true;
                } finally {
                    deadline.cancel(false);
                    opened.close();
                }
                latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
                var usage = usageText(captured.toString());
                billableResponse = true;
                observe(key, used, false, latencyMs);
                if (!validUsage(usage)) {
                    budget.markPending(key.alias, requestId);
                    return line(key.alias(), used, -1, -1, null, requestId, latencyMs, false, "PENDING", maxCost, traceId, route(i), -1);
                }
                var node = read(usage);
                var cost = costOf(used, node);
                budget.settle(key.alias, requestId, cost);
                int inputTokens = tokens(node, "prompt_tokens");
                int outputTokens = tokens(node, "completion_tokens");
                tokensUsed = inputTokens + outputTokens;
                return line(key.alias(), used, inputTokens, outputTokens, cost, requestId, latencyMs, false,
                        "SETTLED", BigDecimal.ZERO, traceId, route(i), cacheTokens(node));
            }
            throw new GatewayException(ErrorCode.SERVER_INTERNAL_ERROR, "上游失败");
        } finally {
            if (!billableResponse) budget.release(key.alias, requestId);
            admission.leave(key, tokensUsed);
        }
    }

    private BigDecimal reservationCost(List<String> attempts, String path) {
        BigDecimal highest = BigDecimal.ZERO;
        for (var name : attempts) {
            var model = catalog.require(name);
            var estimated = model.inputCnyPerToken().multiply(BigDecimal.valueOf(model.maxInputTokens()));
            if (!"/v1/embeddings".equals(path)) {
                estimated = estimated.add(model.outputCnyPerToken().multiply(BigDecimal.valueOf(model.maxOutputTokens())));
            }
            highest = highest.max(estimated);
        }
        return highest.setScale(6, RoundingMode.CEILING);
    }

    private Spend line(String alias, String model, int inputTokens, int outputTokens, BigDecimal cost, String requestId,
                       long latencyMs, boolean timedOut, String status, BigDecimal reserved, String traceId,
                       String routeVersion, int cacheTokens) {
        var row = new Spend(alias, model, inputTokens, outputTokens, cost, requestId, Instant.now(), latencyMs, timedOut,
                status, reserved, UUID.randomUUID().toString(), traceId == null ? "" : traceId,
                catalog.require(model).version(), routeVersion, cacheTokens);
        spendLog.append(row);
        if (history != null && history != spendLog) {
            history.append(row);
        }
        return row;
    }

    private static String route(int index) {
        return index <= 0 ? "primary" : "fallback";
    }

    private static void limit(Integer value) {
        if (value != null && value < 1) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "速率上限必须大于 0");
        }
    }

    private static String usageText(String captured) {
        var found = "";
        for (var line : captured.split("\n")) {
            var trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            var data = trimmed.substring(5).trim();
            if (!data.isBlank() && !" [DONE]".equals(data) && !"[DONE]".equals(data) && data.contains("\"usage\"")) {
                found = data;
            }
        }
        return found;
    }

    public List<Map<String,Object>> logicalRoutingSnapshotRules() {
        return routes.table.current().rules().stream().map(r -> Map.<String,Object>of("logical",r.logical(),"env",r.env(),"condition","env="+r.env(),
                "primary",r.primary(),"secondary",r.secondary(),"primaryWeight",r.primaryWeight(),"fallback",r.fallback(),"gray",r.grayPercent()>0?r.grayModel()+" "+r.grayPercent()+"%":"—")).toList();
    }

    public RoutePolicy.Version routePolicy() {
        return routes.current();
    }

    public RoutePolicy.Version publishRoute(String objective) {
        return routes.publish(objective);
    }

    public RoutePolicy.Version rollbackRoute() {
        return routes.rollback();
    }

    public Preview preview(String alias, String model) {
        var key = requireAlias(alias);
        var snapshot=routes.table.current();
        var row=RouteTable.match(snapshot.rules(),model,envOf(alias));
        var live = row==null?liveModel(model,alias):row.select(newRequestId());
        if (!key.models().contains(model) && !key.models().contains(live)) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "模型不在该 Key 的允许列表里");
        }
        var version = routes.current();
        return new Preview(row==null?version.version():snapshot.version(), version.objective(), namedAttempts(key,row,live,false,JSON.createObjectNode()));
    }

    /** Read-only plan: enumerate every branch, never select randomly or invoke the upstream. */
    public Map<String,Object> diagnose(String alias, String model) {
        var key=requireAlias(alias);
        var snapshot=routes.table.current();
        var row=RouteTable.match(snapshot.rules(),model,envOf(alias));
        var weights=new java.util.LinkedHashMap<String,Double>();
        var warnings=new ArrayList<String>();
        if(key.blocked()) warnings.add("Key 已停用，请求会被拒绝");
        if(row==null) {
            if(routes.table.logical(model))throw RouteTable.invalid("该逻辑模型在此环境没有已发布规则");
            var live=liveModel(model,alias);
            weights.put(live,100d);
            if(!live.equals(model))warnings.add("兼容模型别名映射: "+model+" → "+live);
            warnings.add("真实模型请求不参与逻辑模型分流，降级使用该 Key 的配置");
            // Include Key fallbacks even when they are currently unavailable.
            if(key.allowFallback())for(var fallback:key.fallback())weights.putIfAbsent(fallback,0d);
        } else {
            double normal=(100-row.grayPercent())/100d;
            weights.merge(row.primary(),normal*row.primaryWeight(),Double::sum);
            weights.merge(row.secondary(),normal*(100-row.primaryWeight()),Double::sum);
            if(row.grayPercent()>0)weights.merge(row.grayModel(),(double)row.grayPercent(),Double::sum);
            weights.entrySet().removeIf(e->e.getValue()==0);
            for(var target:row.targets())weights.putIfAbsent(target,0d);
            if(row.minInputTokens()>0)warnings.add("规则要求输入长度估算至少 "+row.minInputTokens()+"；本次仅检查路由配置，未验证输入长度");
        }
        var targets=new ArrayList<Map<String,Object>>();
        for(var entry:weights.entrySet()) {
            var target=entry.getKey(); boolean authorized=covers(key,target);
            var item=new java.util.LinkedHashMap<String,Object>();
            item.put("model",target);item.put("weight",entry.getValue());item.put("authorized",authorized);
            item.put("breakerOpen",routes.open(breakerKey(key,target)));
            List<String> plan=List.of();String warning="";
            if(!authorized)warning="缺少同环境模型/渠道授权";
            else if(entry.getValue()>0) {
                try {plan=row==null?attempts(key,target,false):routeAttempts(key,row,target);}
                catch(GatewayException e){warning=e.getMessage();}
            }
            item.put("attempts",plan);item.put("warning",warning);targets.add(item);
        }
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("alias",alias);result.put("env",envOf(alias));result.put("requestedModel",model);
        result.put("kind",row==null?"concrete":"logical");
        result.put("version",row==null?routes.current().version():snapshot.version());
        result.put("objective",routes.current().objective());result.put("blocked",key.blocked());
        result.put("fallback",row==null?(key.allowFallback()?String.join(" → ",key.fallback()):"none"):row.fallback());
        result.put("minInputTokens",row==null?0:row.minInputTokens());result.put("targets",targets);result.put("warnings",warnings);
        return result;
    }

    private static boolean streamRejected(Upstream.Stream opened) {
        return opened.retryable() || opened.status() >= 400;
    }

    private static String upstreamDetail(Upstream.Stream opened) {
        try {
            var text = new String(opened.body().readNBytes(240), java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ").trim();
            return text.length() > 180 ? text.substring(0, 180) : text;
        } catch (Exception error) {
            return "";
        }
    }

    private static String upstreamFailure(int status, String detail) {
        if (detail == null || detail.isBlank()) {
            return "上游失败 " + status;
        }
        return "上游失败 " + status + " " + detail;
    }

    /** Keys issued before the catalog rename still name qwen-plus and deepseek-v3. Logical names resolve first. */
    private String liveModel(String requested, String alias) {
        if (requested != null && catalog.models().containsKey(requested)) return requested;
        var replacement = switch (requested == null ? "" : requested) {
            case "qwen-plus" -> "qwen3.8-flash";
            case "deepseek-v3" -> "deepseek-flash";
            default -> "";
        };
        if (!replacement.isEmpty() && catalog.models().containsKey(replacement)) {
            return replacement;
        }
        if (requested == null || requested.isBlank() || !catalog.models().containsKey(requested)) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "未知模型: " + requested);
        }
        return requested;
    }

    public AlertRule addAlert(String name, String metric, double threshold, String window, String groupBy) {
        if (name == null || name.isBlank() || threshold < 0) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "告警规则缺少名称或阈值");
        }
        if (!"budget".equals(metric) && !"usage_missing".equals(metric) && !"channel_failure".equals(metric)) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "metric 只能是 budget、usage_missing 或 channel_failure");
        }
        return alertRules.add(name, metric, threshold, window == null ? "" : window, groupBy == null ? "" : groupBy);
    }

    public List<AlertRule> alertRules() {
        return alertRules.list();
    }

    public List<ChannelRow> channelRows() {
        var listed = keys.list();
        var rows = new ArrayList<ChannelRow>();
        for (var model : catalog.models().values()) {
            var id = "m-" + model.name();
            var view = routes.view(model.upstream());
            rows.add(new ChannelRow(id, channelName(model), model.name(), envs(listed, model.name()),
                    ModelCatalog.host(model.upstream()), null, true, prodCapable(listed, model.name()),
                    view.state(), view.openUntil(), boundBuiltin(listed, model.name())));
        }
        for (var added : channels.added()) {
            var model = catalog.models().get(added.model());
            if (model == null) {
                continue;
            }
            var view = routes.view("ch:" + added.id());
            rows.add(new ChannelRow(added.id(), added.name(), added.model(), added.env(),
                    ModelCatalog.host(model.upstream()), added.rpm(), false, "prod".equals(added.env()),
                    view.state(), view.openUntil(), boundAdded(listed, added)));
        }
        return rows;
    }

    public ChannelRow addChannel(String name, String model, String env, int rpm) {
        var trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 40) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "渠道名称需要 1 到 40 个字");
        }
        if (catalog.models().get(model) == null) {
            throw new GatewayException(ErrorCode.SERVER_NOT_FOUND, "模型不存在");
        }
        if (!"prod".equals(env) && !"dev".equals(env) && !"test".equals(env) && !"staging".equals(env)) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "环境只能是 dev、test、staging 或 prod");
        }
        if (rpm < 1) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "厂商上限 RPM 必须大于 0");
        }
        var added = channels.add(trimmed, model, env, rpm);
        return channelRows().stream().filter(row -> row.id().equals(added.id())).findFirst().orElseThrow();
    }

    public SwitchResult switchProd(String id) {
        var target = resolve(id);
        if (!target.model().toLowerCase(java.util.Locale.ROOT).contains("qwen")) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "只能切换 prod 千问渠道");
        }
        if (!target.builtin() && !"prod".equals(target.env())) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "只能切换到 prod 渠道");
        }
        int moved = 0;
        for (var key : keys.list()) {
            if (!key.alias().endsWith("-prod") || key.blocked() || !key.models().contains(target.model())) {
                continue;
            }
            var current = channels.binding(key.alias(), target.model());
            var currentId = current == null ? "m-" + target.model() : current;
            if (currentId.equals(target.id())) {
                continue;
            }
            channels.bind(key.alias(), target.model(), target.builtin() ? null : target.id());
            moved++;
        }
        return new SwitchResult(moved, target.name(), target.id());
    }

    public BreakerResult simulateChannel(String id) {
        var target = resolve(id);
        var view = routes.trip(breakerKey(target));
        return new BreakerResult(target.id(), view.state(), view.openUntil());
    }

    public BreakerResult probeChannel(String id) {
        var target = resolve(id);
        var key = breakerKey(target);
        try {
            probe(target.model());
        } catch (GatewayException error) {
            routes.observe(key, true);
            throw error;
        }
        routes.close(key);
        var view = routes.view(key);
        return new BreakerResult(target.id(), view.state(), view.openUntil());
    }

    private ChannelTarget resolve(String id) {
        if (id != null && id.startsWith("m-")) {
            var modelName = id.substring(2);
            var model = catalog.models().get(modelName);
            if (model == null) {
                throw new GatewayException(ErrorCode.SERVER_NOT_FOUND, "渠道不存在");
            }
            return new ChannelTarget(id, channelName(model), modelName, true, "", model.upstream());
        }
        var added = channels.find(id);
        if (added == null || catalog.models().get(added.model()) == null) {
            throw new GatewayException(ErrorCode.SERVER_NOT_FOUND, "渠道不存在");
        }
        return new ChannelTarget(added.id(), added.name(), added.model(), false, added.env(), "ch:" + added.id());
    }

    private String breakerKey(ChannelTarget target) {
        return target.breakerKey();
    }

    private static String channelName(ModelCatalog.Model model) {
        var provider = ModelCatalog.provider(model.upstream());
        return provider.isBlank() ? model.name() : provider;
    }

    private static String envs(List<VirtualKey> listed, String model) {
        var envs = new ArrayList<String>();
        for (var key : listed) {
            if (!key.models().contains(model)) {
                continue;
            }
            var env = envOf(key.alias());
            if (!env.isEmpty() && !envs.contains(env)) {
                envs.add(env);
            }
        }
        return String.join("、", envs);
    }

    private static boolean prodCapable(List<VirtualKey> listed, String model) {
        for (var key : listed) {
            if (key.alias().endsWith("-prod") && key.models().contains(model)) {
                return true;
            }
        }
        return false;
    }

    private int boundBuiltin(List<VirtualKey> listed, String model) {
        int bound = 0;
        for (var key : listed) {
            if (key.models().contains(model) && channels.binding(key.alias(), model) == null) {
                bound++;
            }
        }
        return bound;
    }

    private int boundAdded(List<VirtualKey> listed, ChannelBoard.Added added) {
        int bound = 0;
        for (var key : listed) {
            if (added.id().equals(channels.binding(key.alias(), added.model()))) {
                bound++;
            }
        }
        return bound;
    }

    private static String envOf(String alias) {
        var dash = alias.lastIndexOf('-');
        if (dash < 0 || !ALIAS.matcher(alias).matches()) {
            return "";
        }
        return alias.substring(dash + 1);
    }

    private static int inputEstimate(JsonNode request) {
        var text=request.path("messages").toString();
        return text.codePointCount(0,text.length());
    }

    private List<String> namedAttempts(VirtualKey key,RouteTable.Rule row,String requested,boolean embedding,JsonNode request) {
        if(row==null || embedding)return attempts(key,requested,embedding);
        if(inputEstimate(request)<row.minInputTokens())throw RouteTable.invalid("输入长度估算未达到逻辑模型的最小输入阈值");
        return routeAttempts(key,row,requested);
    }

    private List<String> routeAttempts(VirtualKey key,RouteTable.Rule row,String requested) {
        var candidates=new ArrayList<String>();candidates.add(requested);
        if(!"none".equals(row.fallback())) {
            var pool=new ArrayList<>(row.targets());pool.remove(requested);
            pool.removeIf(m->!covers(key,m));
            if("cost".equals(row.fallback()))pool.sort(java.util.Comparator.comparing(m->catalog.require(m).inputCnyPerToken().add(catalog.require(m).outputCnyPerToken())));
            if("latency".equals(row.fallback()))pool.sort(java.util.Comparator.comparingLong(m->routes.latencyFor(breakerKey(key,m))));
            if(!java.util.Set.of("alternate","cost","latency").contains(row.fallback()))pool.removeIf(m->!m.equals(row.fallback()));
            if(!pool.isEmpty())candidates.add(pool.getFirst());
        }
        for(var model:candidates)if(!covers(key,model))throw RouteTable.invalid("模型不在该Key的同环境渠道允许列表: "+model);
        var available=candidates.stream().filter(m->!routes.open(breakerKey(key,m))).toList();
        if(available.isEmpty())throw new GatewayException(ErrorCode.SERVER_INTERNAL_ERROR,"可用模型渠道已熔断");
        return available;
    }

    boolean covers(VirtualKey key,String model) {
        if(!key.models().contains(model))return false;
        if(key.shares()==null || key.shares().isEmpty())return true;
        return key.shares().stream().anyMatch(share->channelRows().stream().anyMatch(c->c.id().equals(share.id()) && model.equals(c.model())
                && java.util.Arrays.asList(c.env().split("、")).contains(envOf(key.alias()))));
    }

    synchronized void grantRouteModels(String alias,List<String> missing) {
        var key=requireAlias(alias);
        var allowed=new java.util.LinkedHashSet<>(key.models());
        var shares=new ArrayList<>(key.shares()==null?List.<Share>of():key.shares());
        for(var model:missing) {
            var channel=channelRows().stream().filter(c->model.equals(c.model()) && java.util.Arrays.asList(c.env().split("、")).contains(envOf(alias)))
                .filter(c->c.rpm()==null || keys.list().stream().flatMap(k->k.shares()==null?java.util.stream.Stream.<Share>empty():k.shares().stream()).filter(sh->sh.id().equals(c.id())).mapToInt(Share::rpm).sum()<c.rpm())
                .findFirst().orElseThrow(()->RouteTable.invalid("没有可授权的同环境渠道或渠道配额已满: "+alias+" / "+model));
            allowed.add(model);
            if(!shares.isEmpty() && shares.stream().noneMatch(sh->sh.id().equals(channel.id())))shares.add(new Share(channel.id(),1));
        }
        keys.save(new VirtualKey(key.alias(),key.token(),List.copyOf(allowed),key.fallback(),key.dailyBudgetCny(),key.allowFallback(),key.blocked(),key.version(),key.rpm(),key.tpm(),key.concurrency(),List.copyOf(shares)));
    }

    private List<String> attempts(VirtualKey key, String requested, boolean embedding) {
        var planned = routes.plan(key, requested, embedding, catalog, breakerKey(key, requested));
        if (planned.isEmpty()) throw new GatewayException(ErrorCode.SERVER_INTERNAL_ERROR, "可用模型渠道已熔断");
        return planned;
    }

    private void observe(VirtualKey key, String model, boolean failed, long latencyMs) {
        routes.observe(breakerKey(key, model), failed, latencyMs);
    }

    private void countShare(VirtualKey key, String model, String requestId, String traceId, String routeVersion) {
        var share = shareFor(key, model);
        if (share == null) {
            return;
        }
        try {
            admission.enterShare(key.alias(), share.id(), share.rpm());
        } catch (GatewayException denied) {
            line(key.alias(), model, 0, 0, BigDecimal.ZERO, requestId, 0, false,
                    "FAILED", BigDecimal.ZERO, traceId, "rejected:" + denied.code().name(), 0);
            throw denied;
        }
    }

    private Share shareFor(VirtualKey key, String model) {
        var shares = key.shares();
        if (shares == null || shares.isEmpty()) {
            return null;
        }
        var bound = channels.binding(key.alias(), model);
        if (bound != null) {
            for (var share : shares) {
                if (bound.equals(share.id())) {
                    return share;
                }
            }
        }
        var builtin = "m-" + model;
        for (var share : shares) {
            if (builtin.equals(share.id())) {
                return share;
            }
        }
        for (var share : shares) {
            var row = channelRows().stream().filter(item -> item.id().equals(share.id())).findFirst().orElse(null);
            if (row != null && model.equals(row.model())) {
                return share;
            }
        }
        return null;
    }

    private String breakerKey(VirtualKey key, String model) {
        var share=shareFor(key,model);
        if(share!=null && !share.id().startsWith("m-")) return "ch:"+share.id();
        var bound = channels.binding(key.alias(), model);
        if (bound == null || bound.isBlank()) {
            return catalog.require(model).upstream();
        }
        return "ch:" + bound;
    }

    private VirtualKey virtualKey(String bearer) {
        if (bearer == null || !bearer.startsWith("Bearer ")) {
            throw new GatewayException(ErrorCode.LLM_KEY_BLOCKED, ErrorCode.LLM_KEY_BLOCKED.message());
        }
        VirtualKey key;
        synchronized (this) {
            key = keys.byToken(bearer.substring("Bearer ".length()).trim());
        }
        if (key == null || key.blocked) {
            throw new GatewayException(ErrorCode.LLM_KEY_BLOCKED, ErrorCode.LLM_KEY_BLOCKED.message());
        }
        return key;
    }

    private BigDecimal costOf(String modelName, JsonNode node) {
        var model = catalog.require(modelName);
        int prompt = tokens(node, "prompt_tokens");
        int cached = Math.max(0, cacheTokens(node));
        var cacheRate = model.cacheReadCnyPerToken() == null ? model.inputCnyPerToken() : model.cacheReadCnyPerToken();
        var input = BigDecimal.valueOf(prompt - cached).multiply(model.inputCnyPerToken())
                .add(BigDecimal.valueOf(cached).multiply(cacheRate));
        var output = BigDecimal.valueOf(tokens(node, "completion_tokens")).multiply(model.outputCnyPerToken());
        return input.add(output).setScale(6, RoundingMode.HALF_UP);
    }

    private int tokens(String body, String field) {
        return tokens(read(body), field);
    }

    private int tokens(JsonNode node, String field) {
        return node.path("usage").path(field).asInt(0);
    }

    private boolean validUsage(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        var usage = read(body).path("usage");
        if (!validCount(usage.path("prompt_tokens")) || !validCount(usage.path("completion_tokens"))) {
            return false;
        }
        var cached = usage.path("prompt_tokens_details").path("cached_tokens");
        return cached.isMissingNode() || cached.isNull()
                || (validCount(cached) && cached.asInt() <= usage.path("prompt_tokens").asInt());
    }

    private int cacheTokens(JsonNode node) {
        var cached = node.path("usage").path("prompt_tokens_details").path("cached_tokens");
        if (cached.isMissingNode() || cached.isNull() || !validCount(cached)) {
            return -1;
        }
        return cached.asInt();
    }

    private static boolean validCount(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt() && node.asInt() >= 0;
    }

    private JsonNode read(String body) {
        try {
            return JSON.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new GatewayException(ErrorCode.SERVER_INVALID_PARAM, "请求体不是 JSON");
        }
    }

    public record ModelReport(ModelCatalog.Model model, String health, boolean drift) {}

    public record Preview(int version, String objective, List<String> models) {}

    public record AlertRule(String id, String name, String metric, double threshold, String window, String groupBy) {}

    public record ChannelRow(String id, String name, String model, String env, String host, Integer rpm,
                             boolean builtin, boolean prod, String breaker, long openUntil, int bound) {}

    public record SwitchResult(int moved, String name, String id) {}

    public record BreakerResult(String id, String breaker, long openUntil) {}

    private record ChannelTarget(String id, String name, String model, boolean builtin, String env, String breakerKey) {}

    public record Created(String alias, String key, int version) {}

    public record VirtualKey(String alias, String token, List<String> models, List<String> fallback,
                             BigDecimal dailyBudgetCny, boolean allowFallback, boolean blocked, int version,
                             Integer rpm, Integer tpm, Integer concurrency, List<Share> shares) {
        public VirtualKey(String alias, String token, List<String> models, List<String> fallback,
                          BigDecimal dailyBudgetCny, boolean allowFallback, boolean blocked, int version,
                          Integer rpm, Integer tpm, Integer concurrency) {
            this(alias, token, models, fallback, dailyBudgetCny, allowFallback, blocked, version,
                    rpm, tpm, concurrency, List.of());
        }

        public VirtualKey(String alias, String token, List<String> models, List<String> fallback,
                          BigDecimal dailyBudgetCny, boolean allowFallback, boolean blocked, int version) {
            this(alias, token, models, fallback, dailyBudgetCny, allowFallback, blocked, version, null, null, null);
        }

        public VirtualKey asBlocked() {
            return new VirtualKey(alias, token, models, fallback, dailyBudgetCny, allowFallback, true, version,
                    rpm, tpm, concurrency, shares == null ? List.of() : shares);
        }

        public VirtualKey withBudget(BigDecimal budget) {
            return new VirtualKey(alias, token, models, fallback, budget, allowFallback, blocked, version,
                    rpm, tpm, concurrency, shares == null ? List.of() : shares);
        }
    }

    public record Share(String id, int rpm) {}

    public record ListedKey(String alias, List<String> models, BigDecimal dailyBudgetCny, BigDecimal spentCny,
                            BigDecimal reservedCny, boolean blocked, int version,
                            Integer rpm, Integer tpm, Integer concurrency, List<Share> shares) {}

    public record Spend(String alias, String model, int inputTokens, int outputTokens, BigDecimal costCny,
                        String requestId, Instant ts, long latencyMs, boolean timedOut, String status,
                        BigDecimal reservedCny, String attemptId, String traceId, String priceVersion,
                        String routeVersion, int cacheTokens) {
        public Spend(String alias, String model, int inputTokens, int outputTokens, BigDecimal costCny,
                     String requestId, Instant ts, long latencyMs, boolean timedOut, String status,
                     BigDecimal reservedCny) {
            this(alias, model, inputTokens, outputTokens, costCny, requestId, ts, latencyMs, timedOut, status,
                    reservedCny, "", "", "", "", -1);
        }

        public Spend(String alias, String model, int inputTokens, int outputTokens, BigDecimal costCny,
                     String requestId, Instant ts, long latencyMs, boolean timedOut) {
            this(alias, model, inputTokens, outputTokens, costCny, requestId, ts, latencyMs, timedOut,
                    "SETTLED", BigDecimal.ZERO);
        }
    }

    public record Completion(int status, String body, Spend spend) {}

    public static final class GatewayException extends RuntimeException {
        private final ErrorCode code;

        public GatewayException(ErrorCode code, String message) {
            super(message);
            this.code = code;
        }

        public ErrorCode code() {
            return code;
        }
    }
}
