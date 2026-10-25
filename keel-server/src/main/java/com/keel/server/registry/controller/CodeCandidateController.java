package com.keel.server.registry.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.common.R;
import com.keel.server.registry.service.CodeCandidateService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class CodeCandidateController {
    private final CodeCandidateService candidates;
    public CodeCandidateController(CodeCandidateService candidates) { this.candidates = candidates; }

    @GetMapping("/api/v1/agents/{name}/candidate")
    public R<Map<String,Object>> read(@PathVariable String name,@RequestParam(defaultValue="staging") String env) {
        return R.ok(candidates.read(name,env));
    }

    @PutMapping("/api/v1/agents/{name}/candidate")
    public R<Map<String, String>> update(@PathVariable String name, @RequestBody JsonNode body) {
        return R.ok(candidates.update(name, body));
    }
}
