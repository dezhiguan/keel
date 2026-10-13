package com.keel.server.devflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Fetches a private, immutable task commit outside the untrusted sandbox pod. */
@Component
public class GitSnapshotClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final String base;
    private final String token;

    public GitSnapshotClient(ObjectMapper json, @Value("${KEEL_GIT_CI_URL:}") String base,
                             @Value("${KEEL_GIT_CI_TOKEN:}") String token) {
        this.json = json;
        this.base = base;
        this.token = token;
    }

    public String fetch(String jobId, String repo, String sha) {
        if (base.isBlank() || token.isBlank()) {
            throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED, "Git 快照服务未配置");
        }
        try {
            var body = json.writeValueAsString(Map.of("jobId", jobId, "repo", repo, "sha", sha));
            var request = HttpRequest.newBuilder(URI.create(base.replaceAll("/$", "") + "/v1/tools/git.repo.snapshot"))
                    .timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED, "无法读取指定任务的 Git 提交");
            }
            var snapshot = json.readTree(response.body());
            if (!sha.equals(snapshot.path("sha").asText()) || !snapshot.path("files").isArray()
                    || snapshot.path("files").isEmpty() || response.body().length() > 950_000) {
                throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED, "Git 快照无效或过大");
            }
            return response.body();
        } catch (KeelException error) {
            throw error;
        } catch (Exception error) {
            throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED, "Git 快照服务不可达");
        }
    }
}
