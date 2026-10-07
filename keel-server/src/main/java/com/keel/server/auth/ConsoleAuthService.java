package com.keel.server.auth;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.common.MeController;
import com.keel.server.integration.authgw.AuthGatewayLoginClient;
import com.keel.server.integration.authgw.ClientAssertion;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConsoleAuthService {
    private static final Logger log = LoggerFactory.getLogger(ConsoleAuthService.class);
    private static final int CAPTCHA_AFTER = 5;
    private static final int LOCK_AFTER = 15;

    private final ConsoleAuthProperties properties;
    private final ConsoleSigningKey signingKey;
    private final ConsoleTokenService tokens;
    private final AuthGatewayLoginClient gateway;
    private final Environment environment;
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> captchas = new ConcurrentHashMap<>();

    public ConsoleAuthService(ConsoleAuthProperties properties, ConsoleSigningKey signingKey, ConsoleTokenService tokens,
                              AuthGatewayLoginClient gateway, Environment environment) {
        this.properties = properties;
        this.signingKey = signingKey;
        this.tokens = tokens;
        this.gateway = gateway;
        this.environment = environment;
    }

    public Map<String, Object> options() {
        return Map.of("methods", java.util.List.of("password", "sms"), "previewEnabled", properties.previewOn());
    }

    public Map<String, String> captcha() {
        if (local()) return newCaptcha("refresh");
        var image = gateway.captcha();
        return Map.of("captchaImage", image.captchaImage(), "challengeId", image.challengeId());
    }

    public Map<String, Object> sendSms(String phone) {
        String normalized = requirePhone(phone);
        if (!local()) gateway.sendLoginCode(normalized);
        log.info("console sms requested phone={}", tail(normalized));
        return Map.of("sent", true, "expiresIn", 300);
    }

    public MeController.CurrentUser loginPassword(String account, String password, String captcha, String challengeId,
                                                   HttpServletResponse response) {
        String name = account == null ? "" : account.trim();
        if (name.isBlank() || password == null || password.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "请输入账号和密码");
        }
        try {
            MeController.CurrentUser user = local()
                    ? localPassword(name, password, captcha, challengeId, response)
                    : accept(gateway.password(name, password, captcha, challengeId, clientId(), assertion()), response);
            log.info("console login success account={}", name);
            return user;
        } catch (KeelException e) {
            log.info("console login failed account={} code={}", name, e.code().name());
            throw e;
        }
    }

    public MeController.CurrentUser loginSms(String phone, String code, HttpServletResponse response) {
        String normalized = requirePhone(phone);
        if (code == null || code.isBlank()) throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "请输入短信验证码");
        try {
            MeController.CurrentUser user = local()
                    ? localSms(normalized, code.trim(), response)
                    : accept(gateway.sms(normalized, code.trim(), clientId(), assertion()), response);
            log.info("console sms login success phone={}", tail(normalized));
            return user;
        } catch (KeelException e) {
            log.info("console sms login failed phone={} code={}", tail(normalized), e.code().name());
            throw e;
        }
    }

    public void refresh(String refreshToken, HttpServletResponse response) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new KeelException(ErrorCode.AUTH_UNAUTHENTICATED, ErrorCode.AUTH_UNAUTHENTICATED.message());
        }
        if (local()) {
            var issued = tokens.rotateLocal(refreshToken);
            if (issued == null) throw new KeelException(ErrorCode.AUTH_UNAUTHENTICATED, "登录已失效，请重新登录");
            write(response, issued.accessToken(), issued.refreshToken(), issued.expiresIn(), issued.refreshExpiresIn());
            return;
        }
        var issued = gateway.refresh(refreshToken, clientId(), assertion());
        var verified = tokens.verify(issued.accessToken());
        if (verified.kind() != ConsoleTokenService.VerifyResult.Kind.OK || ConsoleUsers.find(verified.username()).isEmpty()) {
            throw new KeelException(ErrorCode.AUTH_UNAUTHENTICATED, "登录已失效，请重新登录");
        }
        write(response, issued.accessToken(), issued.refreshToken(), issued.expiresIn(), issued.refreshExpiresIn());
    }

    public void logout(String accessToken, String refreshToken) {
        if (local()) tokens.revokeLocal(refreshToken);
        else gateway.logout(accessToken);
        log.info("console logout");
    }

    private MeController.CurrentUser localPassword(String account, String password, String captcha, String challengeId,
                                                    HttpServletResponse response) {
        String expected = properties.getAuth().getLocalPassword();
        if (expected == null || expected.isBlank()) {
            throw new KeelException(ErrorCode.AUTH_GATEWAY_UNAVAILABLE, "登录口令还没有配置，请联系平台管理员");
        }
        guard(account, captcha, challengeId);
        if (!ConsoleUsers.find(account).isPresent() || !constantEquals(expected, password)) {
            throw fail(account);
        }
        attempts.remove(account);
        return issueLocal(response);
    }

    private MeController.CurrentUser localSms(String phone, String code, HttpServletResponse response) {
        String expected = properties.getAuth().getLocalSmsCode();
        if (expected == null || expected.isBlank()) {
            throw new KeelException(ErrorCode.AUTH_GATEWAY_UNAVAILABLE, "短信验证码还没有配置，请联系平台管理员");
        }
        if (!constantEquals(expected, code)) {
            throw new KeelException(ErrorCode.AUTH_BAD_CREDENTIALS, ErrorCode.AUTH_BAD_CREDENTIALS.message());
        }
        return issueLocal(response);
    }

    private MeController.CurrentUser issueLocal(HttpServletResponse response) {
        var issued = tokens.issueLocal("local-" + ConsoleUsers.GUAN.username(), ConsoleUsers.GUAN.username(), ConsoleUsers.GUAN.platformRole());
        write(response, issued.accessToken(), issued.refreshToken(), issued.expiresIn(), issued.refreshExpiresIn());
        return principal(ConsoleUsers.GUAN, "local-" + ConsoleUsers.GUAN.username(), java.util.List.of("ADMIN")).toUser();
    }

    private MeController.CurrentUser accept(AuthGatewayLoginClient.Issued issued, HttpServletResponse response) {
        if (issued.accessToken().isBlank()) {
            throw new KeelException(ErrorCode.AUTH_GATEWAY_UNAVAILABLE, ErrorCode.AUTH_GATEWAY_UNAVAILABLE.message());
        }
        var verified = tokens.verify(issued.accessToken());
        if (verified.kind() == ConsoleTokenService.VerifyResult.Kind.AUDIENCE) {
            throw new KeelException(ErrorCode.AUTH_TOKEN_AUDIENCE, ErrorCode.AUTH_TOKEN_AUDIENCE.message());
        }
        if (verified.kind() != ConsoleTokenService.VerifyResult.Kind.OK) {
            throw new KeelException(ErrorCode.AUTH_GATEWAY_UNAVAILABLE, ErrorCode.AUTH_GATEWAY_UNAVAILABLE.message());
        }
        var user = ConsoleUsers.find(verified.username()).orElseThrow(() ->
                new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, ErrorCode.AUTH_CONSOLE_FORBIDDEN.message()));
        write(response, issued.accessToken(), issued.refreshToken(), issued.expiresIn(), issued.refreshExpiresIn());
        return principal(user, verified.userId(), verified.roles()).toUser();
    }

    private void write(HttpServletResponse response, String access, String refresh, long accessTtl, long refreshTtl) {
        var names = ConsoleCookies.names(environment.matchesProfiles("local"));
        ConsoleCookies.write(response, names.access(), access, names.secure(), accessTtl);
        ConsoleCookies.write(response, names.refresh(), refresh, names.secure(), refreshTtl);
    }

    private void guard(String account, String captcha, String challengeId) {
        Attempt attempt = attempts.get(account);
        if (attempt != null && attempt.lockedUntil != null && attempt.lockedUntil.isAfter(Instant.now())) {
            throw new KeelException(ErrorCode.AUTH_LOCKED, ErrorCode.AUTH_LOCKED.message());
        }
        if (attempt != null && attempt.failures >= CAPTCHA_AFTER) {
            String answer = challengeId == null ? null : captchas.get(challengeId);
            if (captcha == null || answer == null || !answer.equalsIgnoreCase(captcha.trim())) {
                throw captchaRequired("图形验证码不正确，请重新输入");
            }
        }
    }

    private KeelException fail(String account) {
        Attempt attempt = attempts.compute(account, (key, current) -> {
            Attempt next = current == null ? new Attempt() : current;
            next.failures++;
            if (next.failures >= LOCK_AFTER) next.lockedUntil = Instant.now().plusSeconds(15 * 60);
            return next;
        });
        if (attempt.failures >= LOCK_AFTER) {
            return new KeelException(ErrorCode.AUTH_LOCKED, ErrorCode.AUTH_LOCKED.message());
        }
        if (attempt.failures >= CAPTCHA_AFTER) return captchaRequired(ErrorCode.AUTH_CAPTCHA_REQUIRED.message());
        return new KeelException(ErrorCode.AUTH_BAD_CREDENTIALS, ErrorCode.AUTH_BAD_CREDENTIALS.message());
    }

    private KeelException captchaRequired(String message) {
        return new KeelException(ErrorCode.AUTH_CAPTCHA_REQUIRED, message, new LinkedHashMap<>(newCaptcha("shown")));
    }

    private Map<String, String> newCaptcha(String ignored) {
        String answer = UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
        String challengeId = UUID.randomUUID().toString();
        captchas.put(challengeId, answer);
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='120' height='40'><rect width='120' height='40' fill='#1a2438'/>"
                + "<text x='12' y='28' fill='#eef3fa' font-size='22' font-family='monospace' letter-spacing='4'>" + answer + "</text></svg>";
        String image = "data:image/svg+xml," + URLEncoder.encode(svg, StandardCharsets.UTF_8);
        return Map.of("captchaImage", image, "challengeId", challengeId);
    }

    private ConsolePrincipal principal(ConsoleUsers.ConsoleUser user, String userId, java.util.List<String> roles) {
        return new ConsolePrincipal("USER", userId, user.username(), user.displayName(), user.org(), user.platformRole(), roles, false);
    }

    private boolean local() { return "local".equals(properties.mode()); }

    private String clientId() { return properties.getAuth().getClientId(); }

    private String assertion() {
        return ClientAssertion.sign(signingKey.privatePem(), signingKey.kid(), clientId(), properties.getAuth().getAssertionAudience());
    }

    private static String requirePhone(String phone) {
        String value = phone == null ? "" : phone.trim();
        if (!value.matches("1\\d{10}")) throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "请输入 11 位中国大陆手机号");
        return value;
    }

    private static String tail(String phone) {
        return phone.length() < 4 ? "****" : "****" + phone.substring(phone.length() - 4);
    }

    private static boolean constantEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Attempt {
        private int failures;
        private Instant lockedUntil;
    }
}
