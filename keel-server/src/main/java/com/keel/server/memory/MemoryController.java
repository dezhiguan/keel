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
    public R<Map<String, Object>> list(@PathVariable String name, @RequestParam(required = false) String ownerUser) {
        return R.ok(memories.list(name, ownerUser));
    }

    @PostMapping
    public R<Map<String, Object>> write(@PathVariable String name, @RequestBody JsonNode body) {
        return R.ok(memories.write(name, body, ConsolePrincipal.requireUsername()));
    }

    @DeleteMapping("/{id}")
    public R<Map<String, Object>> delete(@PathVariable String name, @PathVariable long id) {
        return R.ok(memories.delete(name, id, ConsolePrincipal.requireUsername()));
    }
}
