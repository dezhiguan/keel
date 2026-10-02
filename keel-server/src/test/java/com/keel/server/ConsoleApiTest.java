package com.keel.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed-local")
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class ConsoleApiTest {
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Autowired MockMvc mvc;

    @Test void listsAgentsWithoutSharedServices() throws Exception {
        mvc.perform(get("/api/v1/agents").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{32}")))
                .andExpect(jsonPath("$.data.total").value(10))
                .andExpect(jsonPath("$.data.items[?(@.name == 'rag-forge')]").isEmpty());
    }

    @Test void filtersByStatusAndKeyword() throws Exception {
        mvc.perform(get("/api/v1/agents").param("status", "OFFLINE"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("ci-doctor"));
        mvc.perform(get("/api/v1/agents").param("q", "copilot"))
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test void pagesResults() throws Exception {
        mvc.perform(get("/api/v1/agents").param("page", "2").param("size", "10"))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test void rejectsSizeOutsideContract() throws Exception {
        mvc.perform(get("/api/v1/agents").param("size", "7"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SERVER_INVALID_PARAM"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test void rejectsUnknownStatus() throws Exception {
        mvc.perform(get("/api/v1/agents").param("status", "BROKEN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SERVER_INVALID_PARAM"));
    }

    @Test void overviewCountsNonRetiredAgents() throws Exception {
        mvc.perform(get("/api/v1/insight/overview").header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.data.kpi.totalAgents").value(10))
                .andExpect(jsonPath("$.data.kpi.onlineAgents").value(6))
                .andExpect(jsonPath("$.data.agents", hasSize(10)))
                .andExpect(jsonPath("$.data.alerts", hasSize(0)));
    }

    @Test void rejectsUnknownRange() throws Exception {
        mvc.perform(get("/api/v1/insight/overview").param("range", "1y"))
                .andExpect(status().isBadRequest());
    }

    @Test void returnsCurrentUser() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(jsonPath("$.data.platformRole").value("ADMIN"));
    }

    @Test void unknownPathUsesErrorBody() throws Exception {
        mvc.perform(get("/api/v1/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SERVER_NOT_FOUND"));
    }
}
