package com.keel.server.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.R;
import com.keel.server.evaluation.EvalJobs;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final EvalJobs eval;

    public SkillController(SkillService skills, EvalJobs eval) {
        this.skills = skills;
        this.eval = eval;
    }

    @GetMapping("/skills")
    public R<Map<String, Object>> list() {
        return R.ok(skills.list());
    }

    @GetMapping("/skills/{name}")
    public R<Map<String, Object>> detail(@PathVariable String name) {
        return R.ok(skills.detail(name));
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
        var principal = ConsolePrincipal.current();
        if (principal != null && "SERVICE".equals(principal.mode()) && !name.equals(principal.userId())) {
            throw new com.keel.server.common.KeelException(
                    com.keel.common.error.ErrorCode.AUTH_CONSOLE_FORBIDDEN,
                    com.keel.common.error.ErrorCode.AUTH_CONSOLE_FORBIDDEN.message());
        }
        return R.ok(skills.load(name, skill));
    }

    @DeleteMapping("/agents/{name}/skills/{skill}")
    public R<Map<String, Object>> revoke(@PathVariable String name, @PathVariable String skill) {
        ConsolePrincipal.requireUsername();
        return R.ok(skills.revoke(name, skill));
    }

    @PostMapping("/agents/{name}/skills/{skill}/upgrade")
    public R<Map<String, Object>> upgrade(@PathVariable String name, @PathVariable String skill) {
        var user = ConsolePrincipal.requireUsername();
        skills.assertCanUpgrade(name, skill);
        return R.ok(skills.trackUpgrade(name, skill, user, eval.start(name)));
    }
}
