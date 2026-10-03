package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

@Service
public class ManifestValidator {
    public void validate(JsonNode manifest, Set<String> grantedSharedTools) {
        if (manifest == null || !"keel/v1".equals(manifest.path("apiVersion").asText())) {
            throw invalid("apiVersion 必须是 keel/v1");
        }
        var name = manifest.path("metadata").path("name").asText("");
        if (AgentNames.rejection(name) != null) {
            throw invalid("名称不合法");
        }
        var owner = manifest.path("metadata").path("owner").asText("");
        if (owner.isBlank()) {
            throw invalid("owner 不能为空");
        }
        var endpoint = manifest.path("spec").path("runtime").path("endpoint").asText("");
        if (endpoint.isBlank()) {
            throw invalid("缺少 endpoint");
        }
        var granted = grantedSharedTools == null ? Set.<String>of() : grantedSharedTools;
        for (var tool : manifest.path("spec").path("tools")) {
            var toolName = tool.path("name").asText("");
            if ("high".equals(tool.path("risk").asText()) && !"required".equals(tool.path("approval").asText())) {
                throw invalid("高风险工具 " + toolName + " 没有绑定审批");
            }
            if (!toolName.isBlank() && !granted.contains(toolName) && tool.path("shared").asBoolean(false)) {
                throw invalid("共享工具 " + toolName + " 未授权");
            }
        }
    }

    public void validateShared(JsonNode manifest, Set<String> sharedToolNames, Set<String> grantedToolNames) {
        validate(manifest, unionGranted(manifest, sharedToolNames, grantedToolNames));
    }

    private static Set<String> unionGranted(JsonNode manifest, Set<String> sharedToolNames, Set<String> grantedToolNames) {
        var granted = new HashSet<String>();
        if (grantedToolNames != null) {
            granted.addAll(grantedToolNames);
        }
        var shared = sharedToolNames == null ? Set.<String>of() : sharedToolNames;
        for (var tool : manifest.path("spec").path("tools")) {
            var toolName = tool.path("name").asText("");
            if (shared.contains(toolName) && !granted.contains(toolName)) {
                throw invalid("共享工具 " + toolName + " 未授权");
            }
        }
        return granted;
    }

    private static KeelException invalid(String message) {
        return new KeelException(ErrorCode.AGENT_MANIFEST_INVALID, message);
    }
}
