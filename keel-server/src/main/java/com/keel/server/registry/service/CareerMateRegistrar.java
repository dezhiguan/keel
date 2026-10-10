package com.keel.server.registry.service;

import com.keel.server.registry.model.enums.AgentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Registers the already-deployed CareerMate process so the console can call it. */
@Component
public class CareerMateRegistrar implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CareerMateRegistrar.class);
    static final String NAME = "careermate";

    private final JdbcTemplate jdbc;
    private final String endpoint;

    public CareerMateRegistrar(JdbcTemplate jdbc, @Value("${KEEL_CAREERMATE_ENDPOINT:}") String endpoint) {
        this.jdbc = jdbc;
        this.endpoint = endpoint == null ? "" : endpoint.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled(endpoint)) {
            return;
        }
        try {
            var manifest = manifest(endpoint);
            jdbc.update("""
                    INSERT INTO agent (name, display_name, kind, runtime, language, owner_org, owner_user, status, liveness)
                    VALUES (?, '职业助手', 'AGENT', 'code', 'Java', '人力数字化组', 'lin', ?, 'k8s')
                    ON CONFLICT (name) DO UPDATE SET display_name = EXCLUDED.display_name,
                        runtime = EXCLUDED.runtime, language = EXCLUDED.language, updated_at = now()
                    """, NAME, AgentStatus.ONLINE.name());
            jdbc.update("""
                    INSERT INTO agent_version (agent_name, version, env, manifest_json, manifest_hash, released_by)
                    VALUES (?, 'v1', 'prod', ?::jsonb, ?, 'keel')
                    ON CONFLICT (agent_name, env, version) DO UPDATE SET manifest_json = EXCLUDED.manifest_json,
                        manifest_hash = EXCLUDED.manifest_hash, released_at = now()
                    """, NAME, manifest, sha256(manifest));
        } catch (RuntimeException e) {
            log.warn("careermate was not registered: {}", e.toString());
        }
    }

    static boolean enabled(String endpoint) {
        return endpoint != null && !endpoint.isBlank();
    }

    static String manifest(String endpoint) {
        return """
                {"apiVersion":"keel/v1","kind":"Agent","metadata":{"name":"careermate","displayName":"职业助手","owner":"人力数字化组 / lin"},"spec":{"runtime":{"type":"code","language":"java","endpoint":"%s","liveness":"k8s"},"auth":{"audience":"careermate"},"models":{"default":"qwen-plus","budget":{"dailyCny":30}},"eval":{"dataset":"careermate/resume-review"}}}
                """.formatted(endpoint).trim();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
