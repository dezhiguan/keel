package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Turns wizard tool names into manifest entries using the shared-tool catalog. */
public final class WizardTools {
    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_.:-]{1,128}");

    private WizardTools() {}

    public record Row(String name, String status, String access, String risk, String version) {}

    public record Spec(String name, String access, String risk, String version) {
        public boolean high() {
            return "high".equals(risk);
        }
    }

    public static List<Spec> resolve(JsonNode form, List<Row> catalog, Set<String> policyIds) {
        if (form != null && form.has("apiVersion")) {
            return List.of();
        }
        var requested = names(form);
        if (requested.isEmpty()) {
            return List.of();
        }
        var byName = new LinkedHashMap<String, Row>();
        for (var row : catalog) {
            if (row != null && row.name() != null && !row.name().isBlank()) {
                byName.putIfAbsent(row.name(), row);
            }
        }
        var resolved = new ArrayList<Spec>();
        var high = false;
        for (var name : requested) {
            if (!NAME.matcher(name).matches()) {
                throw invalid("共享工具 " + name + " 不在注册表里或已下线");
            }
            var row = byName.get(name);
            if (row == null || "RETIRED".equals(row.status())) {
                throw invalid("共享工具 " + name + " 不在注册表里或已下线");
            }
            var access = lower(row.access(), "read");
            var risk = lower(row.risk(), "low");
            if (!access.equals("read") && !access.equals("write") && !access.equals("exec")) {
                throw invalid("共享工具 " + name + " 的读写属性无法识别");
            }
            if (!risk.equals("low") && !risk.equals("mid") && !risk.equals("high")) {
                throw invalid("共享工具 " + name + " 的风险等级无法识别");
            }
            var version = row.version() == null || row.version().isBlank() ? "v1" : row.version();
            resolved.add(new Spec(name, access, risk, version));
            high = high || "high".equals(risk);
        }
        if (high && !policyIds.contains(policyId(form))) {
            throw invalid("高风险工具必须选择审批策略");
        }
        return List.copyOf(resolved);
    }

    private static List<String> names(JsonNode form) {
        var tools = form == null ? null : form.get("tools");
        if (tools == null || !tools.isArray()) {
            return List.of();
        }
        var names = new ArrayList<String>();
        for (var item : tools) {
            var name = item.asText("").trim();
            if (!name.isBlank() && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static String policyId(JsonNode form) {
        var node = form.get("approvalPolicyId");
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText("").trim();
    }

    private static String lower(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static KeelException invalid(String message) {
        return new KeelException(ErrorCode.SERVER_INVALID_PARAM, message);
    }
}
