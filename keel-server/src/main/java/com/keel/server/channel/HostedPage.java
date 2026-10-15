package com.keel.server.channel;

/** Hosted chat page. It does not read or set the console session cookie. */
public final class HostedPage {
    private HostedPage() {}

    public static String html(String agent) {
        return """
                <!doctype html>
                <html lang="zh"><head><meta charset="utf-8"><title>%s</title></head>
                <body>
                <script src="/a/widget.js" data-agent="%s" data-kind="hosted"></script>
                </body></html>
                """.formatted(agent, agent);
    }
}
