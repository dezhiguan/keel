package com.keel.starter.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.starter.manifest.AgentRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FeedbackController {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final AgentRegistry registry;
    private final ObjectMapper mapper;

    public FeedbackController(AgentRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    public ResponseEntity<ErrorBody> feedback(HttpServletRequest request) throws java.io.IOException {
        registry.resolve(request);
        Map<String, Object> body;
        try {
            body = mapper.readValue(request.getInputStream(), MAP);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM);
        }
        Object value = body.get("value");
        int score = value instanceof Number number ? number.intValue() : Integer.MIN_VALUE;
        if (!(body.get("trace_id") instanceof String) || (score != 1 && score != -1)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM);
        }
        // TODO(P0-12): forward feedback to the configured Langfuse project.
        return ResponseEntity.noContent().build();
    }
}
