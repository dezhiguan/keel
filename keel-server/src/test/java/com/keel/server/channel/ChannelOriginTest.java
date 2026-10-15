package com.keel.server.channel;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelOriginTest {
    @Test void anUnlistedOriginIsDenied() {
        assertThatThrownBy(() -> ChannelOrigin.require("https://evil.example", List.of("https://hr.example.com")))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.CHANNEL_ORIGIN_DENIED);
        ChannelOrigin.require("https://hr.example.com", List.of("https://hr.example.com"));
    }

    @Test void hostedPageDoesNotMentionTheConsoleCookie() throws Exception {
        var html = HostedPage.html("careermate");
        assertThat(html).contains("/a/widget.js").contains("data-agent=\"careermate\"");
        assertThat(html).doesNotContain("keel_console").doesNotContain("document.cookie");
        var widget = new String(ChannelOriginTest.class.getResourceAsStream("/static/a/widget.js").readAllBytes(), StandardCharsets.UTF_8);
        assertThat(widget).contains("CHANNEL_ORIGIN_DENIED").contains("event: suspend").contains("feedback");
        assertThat(widget).doesNotContain("keel_console");
    }
}
