package com.keel.server.devflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.common.KeelException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitReviewEvidenceTest {
    @Test void reviewEvidenceMustMatchTheCandidateCommitAndRepository() throws Exception {
        var sha = "a".repeat(40);
        try (var server = new MockWebServer()) {
            server.start();
            var client = new GitSnapshotClient(new ObjectMapper(), server.url("/").toString(), "bridge-secret");
            server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                    {"repo":"owner/spec-agent","number":7,"headSha":"%s","protection":"ENFORCED",
                     "ready":true,"reviewers":["human"],"approved":true}
                    """.formatted(sha)));
            var result = client.reviewStatus("DF-9001", "spec-agent", 7, sha);
            assertThat(result.path("approved").asBoolean()).isTrue();
            var request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/v1/tools/git.pr.review.status");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer bridge-secret");
            assertThat(request.getBody().readUtf8()).contains("\"sha\":\"" + sha + "\"");

            server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                    {"repo":"owner/spec-agent","number":7,"headSha":"%s","protection":"ENFORCED",
                     "ready":true,"reviewers":["human"],"approved":true}
                    """.formatted("b".repeat(40))));
            assertThatThrownBy(() -> client.reviewStatus("DF-9001", "spec-agent", 7, sha))
                    .isInstanceOf(KeelException.class);
        }
    }
}
