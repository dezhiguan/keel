package com.keel.server.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.common.R;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/agents/{name}/releases")
public class ReleaseController {
    private final ReleaseService releases;

    public ReleaseController(ReleaseService releases) {
        this.releases = releases;
    }

    @PostMapping
    public R<Map<String, Object>> release(@PathVariable String name, @RequestBody JsonNode body) {
        return R.ok(releases.release(name, body));
    }
}
