package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Copy an agent SSE stream to the console and keep the final or suspended reply. */
public final class DebugSseRelay {
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Final(String answer, String traceId, boolean rememberable) {}

    private DebugSseRelay() {}

    public static Final copy(InputStream input, OutputStream output) throws IOException {
        var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        var event = "";
        var data = new StringBuilder();
        Final seen = null;
        boolean tainted = false;
        boolean failed = false;
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                if (line.isEmpty()) {
                    output.write('\n');
                    output.flush();
                    if ("guardrail".equals(event)) {
                        try {
                            var guard = JSON.readTree(data.toString());
                            tainted |= !guard.path("hit").isBoolean()
                                    || (guard.path("hit").asBoolean() && !"off".equals(guard.path("mode").asText()));
                        } catch (IOException error) {
                            tainted = true;
                        }
                    }
                    if ("error".equals(event)) failed = true;
                    if ("final".equals(event)) seen = parseReply(data.toString(), "answer", !tainted);
                    if ("suspend".equals(event)) seen = parseReply(data.toString(), "prompt", false);
                    event = "";
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith("event:")) event = line.substring("event:".length()).trim();
                else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    data.append(line.substring("data:".length()).trim());
                }
            }
        } catch (IOException error) {
            if (seen == null) throw error;
        }
        if (seen == null && !failed) throw new IOException("智能体流式连接中断，未返回终止事件");
        return seen;
    }

    private static Final parseReply(String body, String field, boolean rememberable) {
        try {
            var node = JSON.readTree(body);
            var trace = node.path("trace_id").asText("");
            if (!node.path(field).isTextual() || trace.isBlank()) return null;
            return new Final(node.path(field).asText(), trace, rememberable);
        } catch (IOException error) {
            return null;
        }
    }
}
