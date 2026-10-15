package com.keel.server.auth;

import com.keel.common.error.ErrorCode;
import com.keel.server.integration.authgw.GatewayErrors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsoleAuthRulesTest {
    @Test void localModeOutsideLocalProfileFailsStartup() {
        assertThatThrownBy(() -> ConsoleAuthRules.check("local", true, false, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local profile");
    }

    @Test void localModeIsAllowedOnTheLocalProfileAndInTests() {
        ConsoleAuthRules.check("local", true, true, false);
        ConsoleAuthRules.check("local", false, false, true);
        ConsoleAuthRules.check("authgw", false, false, false);
    }

    @Test void consoleCookieIsHttpOnlyAndLimitedToTheApiPath() {
        assertThat(ConsoleCookies.names(false).access()).isEqualTo("keel_console_at");
        assertThat(ConsoleCookies.names(false).secure()).isTrue();
        assertThat(ConsoleCookies.names(true).secure()).isFalse();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        ConsoleCookies.write(response, "keel_console_at", "token", true, 60);
        var header = response.getHeader("Set-Cookie");
        assertThat(header).contains("HttpOnly").contains("Path=/api").contains("Secure").doesNotContain("Path=/;");
    }

    @Test void missingModeOrPreviewFailsStartup() {
        assertThatThrownBy(() -> ConsoleAuthRules.check(null, true, true, false))
                .hasMessageContaining("mode");
        assertThatThrownBy(() -> ConsoleAuthRules.check("authgw", null, false, false))
                .hasMessageContaining("preview");
    }

    @Test void gatewayErrorsStayFriendlyAndDoNotLeakTheUpstreamCode() {
        var bad = GatewayErrors.translate(401, "{\"error\":\"BAD_CREDENTIALS\",\"message\":\"bad credentials\"}");
        assertThat(bad.code()).isEqualTo(ErrorCode.AUTH_BAD_CREDENTIALS);
        assertThat(bad.getMessage()).contains("平台管理员").doesNotContain("BAD_CREDENTIALS");

        var captcha = GatewayErrors.translate(423, "{\"error\":\"CAPTCHA_REQUIRED\",\"captchaImage\":\"data:image/png;base64,aa\",\"challengeId\":\"c1\"}");
        assertThat(captcha.code()).isEqualTo(ErrorCode.AUTH_CAPTCHA_REQUIRED);
        assertThat(captcha.details()).containsEntry("challengeId", "c1");

        var locked = GatewayErrors.translate(423, "{\"error\":\"LOGIN_TEMP_LOCKED\"}");
        assertThat(locked.code()).isEqualTo(ErrorCode.AUTH_LOCKED);
        assertThat(locked.getMessage()).contains("15 分钟");

        assertThat(GatewayErrors.notRegistered(409, "{\"error\":\"SMS_LOGIN_NOT_REGISTERED\"}")).isTrue();
        assertThat(GatewayErrors.translate(503, "").code()).isEqualTo(ErrorCode.AUTH_GATEWAY_UNAVAILABLE);
    }
}
