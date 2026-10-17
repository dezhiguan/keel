package com.keel.server.devflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;

/** Fetches a private, immutable task commit outside the untrusted sandbox pod. */
@Component
public class GitSnapshotClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final String base;
    private final String token;
    private final String mergeToken;

    @Autowired
    public GitSnapshotClient(ObjectMapper json, @Value("${KEEL_GIT_CI_URL:}") String base,
                             @Value("${KEEL_GIT_CI_TOKEN:}") String token,
                             @Value("${KEEL_GIT_CI_MERGE_TOKEN:}") String mergeToken) {
        this.json = json;
        this.base = base;
        this.token = token;
        this.mergeToken = mergeToken;
    }

    GitSnapshotClient(ObjectMapper json, String base, String token) {
        this(json, base, token, "");
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
            HttpResponse<String> response = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    break;
                } catch (IOException error) {
                    if (attempt == 2) throw error;
                    Thread.sleep(500L * (attempt + 1));
                }
            }
            if (response.statusCode() != 200) {
                throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED, "无法读取指定任务的 Git 提交");
            }
            var snapshot = json.readTree(response.body());
            if (!sha.equals(snapshot.path("sha").asText()) || !snapshot.path("files").isArray()
                    || snapshot.path("files").isEmpty() || response.body().getBytes(StandardCharsets.UTF_8).length > 950_000) {
                throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED, "Git 快照无效或过大");
            }
            return response.body();
        } catch (KeelException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED, "Git 快照读取中断");
        } catch (Exception error) {
            throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED, "Git 快照服务不可达");
        }
    }

    public JsonNode reviewStatus(String jobId, String repo, int number, String sha) {
        if (base.isBlank() || token.isBlank()) {
            throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED, "Git 评审服务未配置");
        }
        try {
            var body = json.writeValueAsString(Map.of("jobId", jobId, "repo", repo,
                    "number", number, "sha", sha));
            var request = HttpRequest.newBuilder(URI.create(base.replaceAll("/$", "") + "/v1/tools/git.pr.review.status"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED, "无法核验候选 PR 的人工评审");
            }
            var evidence = json.readTree(response.body());
            if (!sha.equals(evidence.path("headSha").asText())
                    || !evidence.path("reviewers").isArray()
                    || !evidence.path("approved").isBoolean()
                    || number != evidence.path("number").asInt(-1)
                    || !evidence.path("repo").asText().endsWith("/" + repo)) {
                throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED, "Git 人工评审证据与候选提交不一致");
            }
            return evidence;
        } catch (KeelException error) {
            throw error;
        } catch (Exception error) {
            throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED, "Git 评审服务不可达");
        }
    }

    public JsonNode mergeApproved(String jobId, String repo, int number, String headSha) {
        if (base.isBlank() || token.isBlank() || mergeToken.isBlank()) {
            throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED, "Git 合并服务未配置");
        }
        try {
            var body = json.writeValueAsString(Map.of("jobId", jobId, "repo", repo,
                    "number", number, "expectedSha", headSha));
            var request = HttpRequest.newBuilder(URI.create(base.replaceAll("/$", "") + "/v1/tools/git.pr.merge"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .header("X-Keel-Merge-Token", mergeToken)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED, "受保护 PR 合并失败；请核查 H4 与 GitHub 门禁");
            }
            var merged = json.readTree(response.body());
            if (!merged.path("merged").asBoolean(false) || !headSha.equals(merged.path("headSha").asText())
                    || !merged.path("sha").asText().matches("[0-9a-f]{40}")) {
                throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED, "GitHub 未确认候选提交已合并");
            }
            return merged;
        } catch (KeelException error) {
            throw error;
        } catch (Exception error) {
            throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED, "受保护 PR 合并服务不可达");
        }
    }
}
