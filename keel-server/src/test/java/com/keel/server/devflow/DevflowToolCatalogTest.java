package com.keel.server.devflow;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DevflowToolCatalogTest {
    @Test
    void migrationRegistersTheSharedResearchToolsAndBindsMergeApproval() throws Exception {
        var sql = Files.readString(Path.of("src/main/resources/db/migration/V12__devflow_tools.sql"));
        var versions = sql.lines().filter(line -> line.contains("'v1'")).toList();

        assertThat(versions).anyMatch(line -> matches(line, "devflow.job.read", "READ", "LOW", "NULL"));
        assertThat(versions).anyMatch(line -> matches(line, "devflow.stage.report", "WRITE", "LOW", "NULL"));
        assertThat(versions).anyMatch(line -> matches(line, "devflow.batch.read", "READ", "LOW", "NULL"));
        assertThat(versions).anyMatch(line -> matches(line, "devflow.catalog.search", "READ", "LOW", "NULL"));
        assertThat(versions).anyMatch(line -> matches(line, "sandbox.run", "EXEC", "MID", "NULL"));
        assertThat(versions).anyMatch(line -> matches(line, "git.repo.create", "WRITE", "MID", "NULL"));
        assertThat(versions).anyMatch(line -> matches(line, "git.branch.push", "WRITE", "LOW", "NULL"));
        assertThat(versions).anyMatch(line -> matches(line, "git.pr.open", "WRITE", "LOW", "NULL"));
        assertThat(versions).anyMatch(line -> matches(line, "git.pr.diff", "READ", "LOW", "NULL"));
        assertThat(versions).anyMatch(line -> matches(line, "git.pr.comment", "WRITE", "MID", "NULL"));
        assertThat(versions).anyMatch(line -> matches(line, "git.pr.merge", "WRITE", "HIGH", "智能体发布审批"));
        assertThat(versions).anyMatch(line -> matches(line, "ci.workflow.dispatch", "EXEC", "MID", "NULL"));
        assertThat(versions).anyMatch(line -> matches(line, "ci.log.fetch", "READ", "LOW", "NULL"));
        assertThat(versions).anyMatch(line -> matches(line, "keel.gate.report.read", "READ", "LOW", "NULL"));

        assertThat(sql).contains("'智能体发布审批', 'tool.call', 'ROLE', 'ADMIN', 10080, 0");
        assertThat(sql).doesNotContain("agent_tool_grant");
        assertThat(versions).hasSize(14);
    }

    private static boolean matches(String line, String name, String access, String risk, String policy) {
        return line.contains("'" + name + "'") && line.contains("'" + access + "'") && line.contains("'" + risk + "'")
                && line.contains(policy);
    }
}
