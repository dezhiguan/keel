package com.keel.server.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class SkillController {
    private final SkillService skills;

    public SkillController(SkillService skills) {
        this.skills = skills;
    }

    @GetMapping("/skills")
    public R<Map<String, Object>> list() {
        return R.ok(skills.list());
    }

    @PostMapping("/skills")
    public R<Map<String, Object>> publish(@RequestBody JsonNode body) {
        return R.ok(skills.publish(body));
    }

    @GetMapping("/agents/{name}/skills")
    public R<Map<String, Object>> granted(@PathVariable String name) {
        return R.ok(skills.granted(name));
    }

    @PostMapping("/agents/{name}/skills")
    public R<Map<String, Object>> grant(@PathVariable String name, @RequestBody JsonNode body) {
        return R.ok(skills.grant(name, body, ConsolePrincipal.requireUsername()));
    }

    @GetMapping("/agents/{name}/skills/{skill}")
    public R<Map<String, Object>> load(@PathVariable String name, @PathVariable String skill) {
        return R.ok(skills.load(name, skill));
    }
}
