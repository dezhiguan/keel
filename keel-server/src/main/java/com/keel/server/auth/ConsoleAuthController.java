package com.keel.server.auth;

import com.keel.server.common.MeController;
import com.keel.server.common.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class ConsoleAuthController {
    private final ConsoleAuthService auth;
    private final ConsoleSigningKey signingKey;
    private final org.springframework.core.env.Environment environment;

    public ConsoleAuthController(ConsoleAuthService auth, ConsoleSigningKey signingKey,
                                 org.springframework.core.env.Environment environment) {
        this.auth = auth;
        this.signingKey = signingKey;
        this.environment = environment;
    }

    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        return R.ok(auth.options());
    }

    @GetMapping("/captcha")
    public R<Map<String, String>> captcha() {
        return R.ok(auth.captcha());
    }

    @GetMapping("/jwks.json")
    public Map<String, Object> jwks(HttpServletResponse response) {
        response.setHeader("Cache-Control", "public, max-age=300");
        return signingKey.jwks();
    }

    @PostMapping("/sms/send")
    public R<Map<String, Object>> send(@RequestBody SmsSend body) {
        return R.ok(auth.sendSms(body.phone()));
    }

    @PostMapping("/login/password")
    public R<MeController.CurrentUser> password(@RequestBody PasswordLogin body, HttpServletResponse response) {
        return R.ok(auth.loginPassword(body.account(), body.password(), body.captcha(), body.challengeId(), response));
    }

    @PostMapping("/login/sms")
    public R<MeController.CurrentUser> sms(@RequestBody SmsLogin body, HttpServletResponse response) {
        return R.ok(auth.loginSms(body.phone(), body.code(), response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(HttpServletRequest request, HttpServletResponse response) {
        var names = ConsoleCookies.names(environment.matchesProfiles("local"));
        auth.refresh(ConsoleCookies.read(request, names, false), response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        var names = ConsoleCookies.names(environment.matchesProfiles("local"));
        auth.logout(ConsoleCookies.read(request, names, true), ConsoleCookies.read(request, names, false));
        ConsoleCookies.clear(response, names);
        return ResponseEntity.noContent().build();
    }

    public record PasswordLogin(String account, String password, String captcha, String challengeId) {}

    public record SmsLogin(String phone, String code) {}

    public record SmsSend(String phone) {}
}
