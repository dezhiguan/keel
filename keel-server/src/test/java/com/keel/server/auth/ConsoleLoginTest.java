package com.keel.server.auth;

import com.keel.server.PostgresTestConfig;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "keel.console.auth.mode=local",
        "keel.console.auth.open-for-tests=false",
        "keel.console.auth.local-password=secret-pass",
        "keel.console.auth.local-sms-code=654321",
        "keel.console.preview.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@ActiveProfiles("local")
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class ConsoleLoginTest {
    @Autowired MockMvc mvc;

    @Test void passwordLoginSetsHttpOnlyCookieAndDoesNotReturnTheToken() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"guandezhi\",\"password\":\"secret-pass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("官德志"))
                .andExpect(jsonPath("$.data.platformRole").value("ADMIN"))
                .andExpect(jsonPath("$.data.mode").value("USER"))
                .andExpect(jsonPath("$.data.readOnly").value(false))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("secret-pass").doesNotContain("access_token");
        String setCookie = String.join("\n", result.getResponse().getHeaders("Set-Cookie"));
        assertThat(setCookie).contains("keel_console_at").contains("HttpOnly").contains("SameSite=Strict");
        assertThat(setCookie).doesNotContain("__Host-").doesNotContain("Secure");

        String access = result.getResponse().getCookie("keel_console_at").getValue();
        mvc.perform(get("/api/v1/me").cookie(new Cookie("keel_console_at", access)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("官德志"));
    }

    @Test void wrongPasswordIsFriendlyAndDoesNotSetACookie() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"nobody\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_BAD_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("平台管理员")))
                .andReturn();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    }

    @Test void previewCanReadButCannotWrite() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("PREVIEW"))
                .andExpect(jsonPath("$.data.displayName").value("预览访客"))
                .andExpect(jsonPath("$.data.readOnly").value(true));
        mvc.perform(post("/api/v1/me"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_PREVIEW_READONLY"));
    }

    @Test void brokenCookieIsNotTreatedAsPreview() throws Exception {
        mvc.perform(get("/api/v1/me").cookie(new Cookie("keel_console_at", "not-a-jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
    }

    @Test void fifthFailureAsksForACaptcha() throws Exception {
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/v1/auth/login/password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"account\":\"captcha-user\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"captcha-user\",\"password\":\"wrong\"}"))
                .andExpect(status().is(423))
                .andExpect(jsonPath("$.code").value("AUTH_CAPTCHA_REQUIRED"))
                .andExpect(jsonPath("$.details.captchaImage").exists())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("图形验证码")));
    }
}
