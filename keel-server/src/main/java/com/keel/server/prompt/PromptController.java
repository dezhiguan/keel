package com.keel.server.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.common.R;
import com.keel.server.release.ReleaseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class PromptController {
    private final PromptService prompts;
    private final ReleaseService releases;

    public PromptController(PromptService prompts, ReleaseService releases) {
        this.prompts = prompts;
        this.releases = releases;
    }

    @GetMapping("/prompts")
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "all") String env,
                                       @RequestParam(required = false) String agent) {
        return R.ok(prompts.list(env, agent));
    }

    @GetMapping("/agents/{name}/prompts/{prompt}")
    public R<Map<String, Object>> detail(@PathVariable String name, @PathVariable String prompt) {
        return R.ok(prompts.detail(name, prompt));
    }

    @GetMapping("/agents/{name}/prompts/{prompt}/versions/{version}")
    public R<Map<String, Object>> version(@PathVariable String name, @PathVariable String prompt, @PathVariable int version) {
        return R.ok(prompts.version(name, prompt, version));
    }

    @GetMapping("/agents/{name}/prompts/{prompt}/diff")
    public R<Map<String, Object>> diff(@PathVariable String name, @PathVariable String prompt,
                                       @RequestParam int from, @RequestParam int to) {
        return R.ok(prompts.diff(name, prompt, from, to));
    }

    @PostMapping("/agents/{name}/prompts/{prompt}/versions")
    public R<Map<String, Object>> save(@PathVariable String name, @PathVariable String prompt, @RequestBody JsonNode body) {
        return R.ok(prompts.save(name, prompt, body));
    }

    @PostMapping("/agents/{name}/prompts/{prompt}/promote")
    public R<Map<String, Object>> promote(@PathVariable String name, @PathVariable String prompt, @RequestBody JsonNode body) {
        return R.ok(prompts.promote(name, prompt, body));
    }

    @PostMapping("/agents/{name}/prompts/{prompt}/rollback")
    public R<Map<String, Object>> rollback(@PathVariable String name, @PathVariable String prompt, @RequestBody JsonNode body) {
        return R.ok(releases.rollback(name, prompt, body.path("version").asInt(), body.path("reason").asText("")));
    }

    @PostMapping("/agents/{name}/prompts/sync")
    public R<Map<String, Object>> sync(@PathVariable String name, @RequestBody JsonNode body) {
        return R.ok(prompts.sync(name, body));
    }
}
