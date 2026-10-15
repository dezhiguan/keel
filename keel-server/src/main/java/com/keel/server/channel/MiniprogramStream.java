package com.keel.server.channel;

/** WeChat miniprograms cannot use EventSource. Chunks are plain text lines. */
public final class MiniprogramStream {
    private MiniprogramStream() {}

    public static String chunks(String text) {
        var body = text == null ? "" : text;
        var out = new StringBuilder();
        var size = 8;
        for (int i = 0; i < body.length(); i += size) {
            out.append(body, i, Math.min(body.length(), i + size)).append('\n');
        }
        return out.toString();
    }
}
