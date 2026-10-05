package com.keel.server.insight;

import com.keel.server.approval.service.ApprovalService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Function;

@Configuration
public class InsightConfiguration {
    @Bean
    Function<String, Integer> pendingApprovals(ApprovalService approvals) {
        return approvals::pendingCount;
    }
}
