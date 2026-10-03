package com.keel.starter.web;

import com.keel.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
        InvokeController.class, HealthController.class, ManifestController.class, FeedbackController.class
})
public class KeelExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(KeelExceptionHandler.class);

    @ExceptionHandler(KeelException.class)
    ResponseEntity<ErrorBody> keel(KeelException ex, HttpServletRequest request) {
        log.error("request rejected trace_id={} agent=unknown code={}", InvokeController.traceId(request), ex.code().name());
        return body(ex.code(), request);
    }

    private static ResponseEntity<ErrorBody> body(ErrorCode code, HttpServletRequest request) {
        return ResponseEntity.status(code.http()).body(ErrorBody.of(code, InvokeController.traceId(request), null));
    }
}
