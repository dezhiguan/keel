package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Copy an agent SSE stream to the console and keep the final answer. */
public final class DebugSseRelay {
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Final(String answer, String traceId) {}

    private DebugSseRelay() {}

    public static Final copy(InputStream input, OutputStream output) throws IOException {
        var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        var event = "";
        var data = new StringBuilder();
        Final seen = null;
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                if (line.isEmpty()) {
                    output.write('\n');
                    output.flush();
                    if ("final".equals(event)) seen = parseFinal(data.toString());
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
        return seen;
    }

    private static Final parseFinal(String body) {
        try {
            var node = JSON.readTree(body);
            var trace = node.path("trace_id").asText("");
            return new Final(node.path("answer").asText(""), trace.isBlank() ? null : trace);
        } catch (IOException error) {
            return new Final("", null);
        }
    }
}
