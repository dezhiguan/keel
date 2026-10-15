package com.keel.server.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.R;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/agents/{name}/memories")
public class MemoryController {
    private final MemoryService memories;

    public MemoryController(MemoryService memories) {
        this.memories = memories;
    }

    @GetMapping
    public R<Map<String, Object>> list(@PathVariable String name,
                                       @RequestParam(required = false) String ownerUser,
                                       @RequestParam(required = false) String layer) {
        return R.ok(memories.list(name, ownerUser, layer));
    }

    @PostMapping("/recall")
    public R<Map<String, Object>> recall(@PathVariable String name, @RequestBody JsonNode body) {
        var query = new java.util.ArrayList<Double>();
        body.path("embedding").forEach(item -> query.add(item.asDouble()));
        return R.ok(memories.recall(name, body.path("ownerUser").asText(""), body.path("layer").asText("USER"), query));
    }

    @PostMapping
    public R<Map<String, Object>> write(@PathVariable String name, @RequestBody JsonNode body) {
        return R.ok(memories.write(name, body, actor()));
    }

    @DeleteMapping("/{id}")
    public R<Map<String, Object>> delete(@PathVariable String name, @PathVariable long id) {
        return R.ok(memories.delete(name, id, actor()));
    }

    private static String actor() {
        var principal = ConsolePrincipal.current();
        if (principal != null && "SERVICE".equals(principal.mode())) return principal.username();
        return ConsolePrincipal.requireUsername();
    }
}
