package com.keel.server.tool.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.common.PageResult;
import com.keel.server.common.R;
import com.keel.server.tool.service.ToolRegistryService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/tools")
public class ToolController {
    private final ToolRegistryService tools;

    public ToolController(ToolRegistryService tools) {
        this.tools = tools;
    }

    @GetMapping
    public R<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|PRIVATE|SHARED") String scope,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String risk,
            @RequestParam(defaultValue = "all") @Pattern(regexp = "all|dev|test|staging|prod") String env,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Pattern(regexp = "10|20|50|100") String size) {
        return R.ok(tools.page(scope, status, risk, env, page, Integer.parseInt(size)));
    }

    @PostMapping
    public R<Map<String, Object>> register(@RequestBody JsonNode body) {
        tools.register(body);
        return R.ok(Map.of("name", body.path("name").asText("")));
    }

    @GetMapping("/{name}")
    public R<Map<String, Object>> detail(@PathVariable String name) {
        return R.ok(tools.detail(name));
    }

    @PostMapping("/{name}/versions")
    public R<Map<String, Object>> publish(@PathVariable String name, @RequestBody JsonNode body) {
        var triggered = tools.publish(name, body);
        return R.ok(Map.of("triggeredRegressions", triggered));
    }

    @PostMapping("/{name}/deprecate")
    public R<Map<String, Object>> deprecate(@PathVariable String name, @RequestBody JsonNode body) {
        tools.deprecate(name, body.path("replacedBy").asText(""), body.path("deadline").asText(""));
        return R.ok(Map.of("name", name));
    }

    @PostMapping("/{name}/retire")
    public R<Map<String, Object>> retire(@PathVariable String name) {
        tools.retire(name);
        return R.ok(Map.of("name", name));
    }
}
