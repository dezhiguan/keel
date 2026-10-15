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
import org.springframework.web.bind.annotation.CrossOrigin;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import com.keel.server.registry.service.AgentChatService;

import java.util.Map;

@RestController
public class ChannelController {
    private final ChannelService channels;
    private final AgentChatService chats;

    public ChannelController(ChannelService channels, AgentChatService chats) {
        this.channels = channels;
        this.chats = chats;
    }

    @GetMapping(value = "/a/{agent:[a-z][a-z0-9-]*}", produces = MediaType.TEXT_HTML_VALUE)
    public String hosted(@PathVariable String agent) {
        return HostedPage.html(agent, channels.hostedCredential(agent));
    }

    @GetMapping("/api/v1/agents/{name}/channels")
    public R<Map<String, Object>> list(@PathVariable String name) {
        return R.ok(channels.list(name));
    }

    @PostMapping("/api/v1/agents/{name}/channels")
    public R<Map<String, Object>> publish(@PathVariable String name, @RequestBody JsonNode body) {
        return R.ok(channels.publish(name, body));
    }

    @CrossOrigin(origins = "*")
    @PostMapping({"/api/v1/agents/{name}/channels/entry", "/a/api/{name}/entry"})
    public R<Map<String, Object>> entry(@PathVariable String name, @RequestBody JsonNode body, HttpServletRequest request) {
        return R.ok(channels.entry(name, body, channelOrigin(request), request.getHeader("Authorization")));
    }

    @CrossOrigin(origins = "*")
    @PostMapping(value = "/a/api/{name}/invoke", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public StreamingResponseBody invoke(@PathVariable String name, @RequestBody JsonNode body, HttpServletRequest request) {
        var credential = body.path("credential").asText("");
        var text = body.path("text").asText("");
        if (text.isBlank()) throw new com.keel.server.common.KeelException(
                com.keel.common.error.ErrorCode.SERVER_INVALID_PARAM, "输入不能为空");
        var userId = channels.admit(name, credential, channelOrigin(request), request.getHeader("Authorization"));
        return output -> {
            try {
                chats.channelStream(name, text, credential, userId, output);
            } catch (RuntimeException error) {
                var event = "event: error\ndata: {\"code\":\"%s\",\"message\":\"调用失败\"}\n\n"
                        .formatted(com.keel.common.error.ErrorCode.SERVER_INTERNAL_ERROR.name());
                output.write(event.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.flush();
            }
        };
    }

    @CrossOrigin(origins = "*")
    @PostMapping("/a/api/{name}/feedback")
    public R<Map<String, Object>> feedback(@PathVariable String name, @RequestBody JsonNode body,
                                            HttpServletRequest request) {
        channels.feedback(name, body.path("credential").asText(""), channelOrigin(request), request.getHeader("Authorization"),
                body.path("traceId").asText(""), body.path("value").asInt(0));
        return R.ok(Map.of("recorded", true));
    }

    private static String channelOrigin(HttpServletRequest request) {
        var origin = request.getHeader("Origin");
        return origin == null || origin.isBlank() ? request.getHeader("X-Keel-Miniapp-Id") : origin;
    }
}
