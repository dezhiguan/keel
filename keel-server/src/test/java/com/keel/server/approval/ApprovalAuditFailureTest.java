package com.keel.server.approval;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.audit.AuditStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import({com.keel.server.PostgresTestConfig.class, ApprovalAuditFailureTest.FailingAudit.class})
@Testcontainers(disabledWithoutDocker = true)
class ApprovalAuditFailureTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test void aFailedAuditLeavesTheTicketPending() throws Exception {
        var created = mvc.perform(post("/api/v1/approvals").contentType("application/json").content("""
                {"subjectType":"data.export","subjectRef":"audit-fail","summary":"审计写失败必须回滚","actorUser":"amy"}
                """)).andExpect(status().isOk()).andReturn();
        var approvalId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.data.id").toString();
        mvc.perform(post("/api/v1/approvals/" + approvalId + "/decision")
                        .contentType("application/json").content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUDIT_WRITE_FAILED"));
        assertThat(jdbc.queryForObject("SELECT status FROM approval_request WHERE subject_ref = 'audit-fail'", String.class))
                .isEqualTo("PENDING");
    }

    @TestConfiguration
    static class FailingAudit {
        @Bean
        @Primary
        AuditStore failingAuditStore() {
            return new AuditStore() {
                @Override
                public void append(String agent, String env, String action, String risk, String decision, String resource, String traceId) {
                    if ("approved".equals(decision)) {
                        throw new KeelException(ErrorCode.AUDIT_WRITE_FAILED, ErrorCode.AUDIT_WRITE_FAILED.message());
                    }
                }

                @Override
                public Map<String, Object> page(String agent, String risk, String env, int page, int size) {
                    return Map.of("page", page, "size", size, "total", 0, "items", List.of());
                }

                @Override
                public Map<String, Object> verify(String agent) {
                    return Map.of("checked", 0, "intact", true, "elapsedMs", 0);
                }
            };
        }
    }
}
