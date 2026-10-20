package com.keel.server.registry.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.common.R;
import com.keel.server.registry.service.ReadCapabilityService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
public class ReadCapabilityController {
    private final ReadCapabilityService capabilities;
    public ReadCapabilityController(ReadCapabilityService capabilities){this.capabilities=capabilities;}
    @PostMapping("/api/v1/agents/{name}/read-capabilities")
    public R<Map<String,String>> approve(@PathVariable String name,@RequestBody JsonNode body){return R.ok(capabilities.approve(name,body));}
}
