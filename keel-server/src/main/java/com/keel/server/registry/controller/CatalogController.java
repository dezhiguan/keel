package com.keel.server.registry.controller;

import com.keel.server.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {
    @GetMapping
    public R<Map<String, Object>> catalog() {
        return R.ok(Map.of(
                "templates", List.of(
                        template("echo", "回声", "python"),
                        template("chat-rag", "对话检索", "python"),
                        template("tool-agent", "工具调用", "python"),
                        template("graph-agent", "多步推理", "python"),
                        template("supervisor", "多智能体", "python"),
                        template("java-spring", "Java 服务", "java")),
                "tools", List.of(),
                "knowledgeBases", List.of(),
                "models", List.of("qwen-plus", "deepseek-v3"),
                "orgs", List.of(),
                "approvalPolicies", List.of()));
    }

    private static Map<String, String> template(String id, String name, String language) {
        return Map.of("id", id, "name", name, "language", language, "description", name);
    }
}
