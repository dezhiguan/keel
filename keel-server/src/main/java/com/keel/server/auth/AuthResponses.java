package com.keel.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.ApiError;
import com.keel.server.common.KeelException;
import jakarta.servlet.http.HttpServletResponse;

import java.util.LinkedHashMap;

final class AuthResponses {
    private static final ObjectMapper JSON = new ObjectMapper();

    private AuthResponses() {}

    static void write(HttpServletResponse response, KeelException error) throws java.io.IOException {
        write(response, error.code().http(), error.code(), error.getMessage(), error.details());
    }

    static void write(HttpServletResponse response, int status, ErrorCode code, String message,
                      java.util.Map<String, Object> details) throws java.io.IOException {
        ApiError error = ApiError.of(code, message);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("code", error.code());
        payload.put("message", error.message());
        payload.put("traceId", error.traceId());
        payload.put("retryable", error.retryable());
        if (details != null && !details.isEmpty()) payload.put("details", details);
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        JSON.writeValue(response.getWriter(), payload);
    }
}
