package com.keel.server.memory;
import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.common.R;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/v1/agents/{name}/memories/maintenance")
public class SessionMemoryController {
    private final SessionMemoryJobs jobs;
    public SessionMemoryController(SessionMemoryJobs jobs){this.jobs=jobs;}
    @PostMapping("/claim") public R<Map<String,Object>> claim(@PathVariable String name,@RequestBody JsonNode b){return R.ok(jobs.claim(name,b.path("env").asText(),b.path("workerId").asText(""),b.path("combine").asBoolean(false)));}
    @PostMapping("/complete") public R<Map<String,Object>> complete(@PathVariable String name,@RequestBody JsonNode b){return R.ok(jobs.complete(name,b.path("env").asText(),b));}
    @PostMapping("/fail") public R<Map<String,Object>> fail(@PathVariable String name,@RequestBody JsonNode b){return R.ok(jobs.fail(name,b.path("env").asText(),b));}
    @PostMapping("/cost") public R<Map<String,Object>> cost(@PathVariable String name,@RequestBody JsonNode b){return R.ok(jobs.costContext(name,b.path("env").asText(),b));}
    @GetMapping("/status") public R<Map<String,Object>> status(@PathVariable String name,@RequestParam String env){return R.ok(jobs.status(name,env));}
}
