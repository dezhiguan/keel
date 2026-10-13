package com.keel.server.integration.langfuse;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseExceptionHandlerTest {
    @Test void rateLimitIsRetryableInsteadOfAnInternalError() {
        var response = new LangfuseExceptionHandler().limited(new LangfuseRateLimit(12));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("12");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INSIGHT_UPSTREAM_UNAVAILABLE.name());
        assertThat(response.getBody().message()).isEqualTo(ErrorCode.INSIGHT_UPSTREAM_UNAVAILABLE.message());
        assertThat(response.getBody().retryable()).isTrue();
    }

    @Test void rateLimitAdviceRunsBeforeTheGenericHandler() {
        var limited = LangfuseExceptionHandler.class.getAnnotation(Order.class);
        assertThat(limited).isNotNull();
        assertThat(limited.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(GlobalExceptionHandler.class.getAnnotation(Order.class)).isNull();
    }
}
