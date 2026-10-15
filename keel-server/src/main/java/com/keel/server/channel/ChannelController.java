package com.keel.server.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.common.R;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ChannelController {
    private final ChannelService channels;

    public ChannelController(ChannelService channels) {
        this.channels = channels;
    }

    @GetMapping(value = "/a/{agent:[a-z][a-z0-9-]*}", produces = MediaType.TEXT_HTML_VALUE)
    public String hosted(@PathVariable String agent) {
        return HostedPage.html(agent);
    }

    @GetMapping("/api/v1/agents/{name}/channels")
    public R<Map<String, Object>> list(@PathVariable String name) {
        return R.ok(channels.list(name));
    }

    @PostMapping("/api/v1/agents/{name}/channels")
    public R<Map<String, Object>> publish(@PathVariable String name, @RequestBody JsonNode body) {
        return R.ok(channels.publish(name, body));
    }

    @PostMapping(value = "/api/v1/agents/{name}/channels/stream", produces = "text/plain;charset=UTF-8")
    public String stream(@PathVariable String name, @RequestBody JsonNode body) {
        channels.entry(name, body);
        return MiniprogramStream.chunks(body.path("text").asText(""));
    }

    @PostMapping("/api/v1/agents/{name}/channels/entry")
    public R<Map<String, Object>> entry(@PathVariable String name, @RequestBody JsonNode body) {
        return R.ok(channels.entry(name, body));
    }
}
