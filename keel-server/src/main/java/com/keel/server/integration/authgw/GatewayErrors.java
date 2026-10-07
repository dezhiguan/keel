package com.keel.server.integration.authgw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;

import java.util.LinkedHashMap;
import java.util.Map;

/** 把 auth-gateway 的错误码翻译成控制台错误，不把网关原文透传给浏览器。 */
public final class GatewayErrors {
    private static final ObjectMapper JSON = new ObjectMapper();

    private GatewayErrors() {}

    public static KeelException translate(int status, String body) {
        JsonNode node = parse(body);
        String error = node.path("error").asText("");
        if (status == 423 && "LOGIN_TEMP_LOCKED".equals(error)) {
            return new KeelException(ErrorCode.AUTH_LOCKED, ErrorCode.AUTH_LOCKED.message());
        }
        if (status == 423 || "CAPTCHA_REQUIRED".equals(error)) {
            Map<String, Object> details = new LinkedHashMap<>();
            if (node.hasNonNull("captchaImage")) details.put("captchaImage", node.get("captchaImage").asText());
            if (node.hasNonNull("challengeId")) details.put("challengeId", node.get("challengeId").asText());
            return new KeelException(ErrorCode.AUTH_CAPTCHA_REQUIRED, ErrorCode.AUTH_CAPTCHA_REQUIRED.message(), details);
        }
        if (status == 429 || error.contains("TOO_MANY") || error.contains("RATE")) {
            return new KeelException(ErrorCode.AUTH_SMS_RATE_LIMITED, ErrorCode.AUTH_SMS_RATE_LIMITED.message());
        }
        if (status == 401 && ("BAD_CREDENTIALS".equals(error) || "SMS_CODE_INVALID".equals(error) || "USER_NOT_FOUND".equals(error))) {
            return new KeelException(ErrorCode.AUTH_BAD_CREDENTIALS, ErrorCode.AUTH_BAD_CREDENTIALS.message());
        }
        if (status == 403) {
            return new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, ErrorCode.AUTH_CONSOLE_FORBIDDEN.message());
        }
        return new KeelException(ErrorCode.AUTH_GATEWAY_UNAVAILABLE, ErrorCode.AUTH_GATEWAY_UNAVAILABLE.message());
    }

    public static boolean notRegistered(int status, String body) {
        return status == 409 && "SMS_LOGIN_NOT_REGISTERED".equals(parse(body).path("error").asText(""));
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) return JSON.createObjectNode();
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }
}
