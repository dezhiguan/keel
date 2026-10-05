package com.keel.audit.masking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.audit.query.AuditLog;
import com.keel.audit.query.ExportService;
import com.keel.audit.store.ArchiveJob;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuditMaskQueryTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void whitelistDropsEveryOtherKey() throws Exception {
        var payload = json.readTree("{\"pr_url\":\"https://example.test/1\",\"secret\":\"keep-me-out\"}");
        var kept = FieldWhitelistFilter.apply(payload, Set.of("pr_url"), "askdb");
        assertThat(kept.fieldNames()).toIterable().containsExactly("pr_url");
    }

    @Test void missingWhitelistClearsThePayload() throws Exception {
        var payload = json.readTree("{\"pr_url\":\"https://example.test/1\"}");
        var kept = FieldWhitelistFilter.apply(payload, null, "askdb");
        assertThat(kept.fieldNames()).toIterable().isEmpty();
    }

    @Test void listedFieldsStillMaskPhoneIdAndBankCard() throws Exception {
        var payload = json.readTree("""
                {"phone":"13812345678","id_no":"110101199001011234","card":"6222021234567890"}
                """);
        var kept = FieldWhitelistFilter.apply(payload, Set.of("phone", "id_no", "card"), "askdb");
        assertThat(kept.get("phone").asText()).isEqualTo("138****5678");
        assertThat(kept.get("id_no").asText()).isEqualTo("110***********1234");
        assertThat(kept.get("card").asText()).isEqualTo("6222********7890");
        assertThat(kept.get("phone").asText()).hasSize(11);
        assertThat(kept.get("id_no").asText()).hasSize(18);
        assertThat(kept.get("card").asText()).hasSize(16);
    }

    @Test void queryFiltersByAgentActionRiskTraceAndTime() throws Exception {
        var log = new AuditLog();
        log.add(json.readTree("""
                {"agent":"askdb","action":"tool.call","risk":"high","trace_id":"t1","ts":"2026-10-04T03:00:00Z"}
                """));
        log.add(json.readTree("""
                {"agent":"askdb","action":"invoke","risk":"low","trace_id":"t2","ts":"2026-10-05T03:00:00Z"}
                """));
        log.add(json.readTree("""
                {"agent":"other","action":"tool.call","risk":"high","trace_id":"t1","ts":"2026-10-04T03:00:00Z"}
                """));
        var from = Instant.parse("2026-10-04T00:00:00Z");
        var to = Instant.parse("2026-10-05T00:00:00Z");
        var page = log.filter("askdb", "tool.call", "high", "t1", from, to, 1, 10);
        assertThat(page).hasSize(1);
        assertThat(page.get(0).path("trace_id").asText()).isEqualTo("t1");
        assertThat(log.filter("askdb", null, null, null, null, null, 2, 1)).hasSize(1);
        assertThat(log.total("askdb", null, null, null, null, null)).isEqualTo(2);
        log.add(json.readTree("""
                {"agent":"askdb","env":"test","action":"invoke","risk":"low","trace_id":"t3","ts":"2026-10-05T04:00:00Z"}
                """));
        assertThat(log.filter("test", "askdb", null, null, null, null, null, 1, 10)).hasSize(1);
        assertThat(log.filter("all", "askdb", null, null, null, null, null, 1, 10)).hasSize(3);
        assertThat(log.total("prod", "askdb", null, null, null, null, null)).isZero();
    }

    @Test void exportHasNoFileUntilApproval() {
        var service = new ExportService((subject, filter) -> {
            assertThat(subject).isEqualTo("data.export");
            return "ap-1";
        });
        var created = service.request(Map.of("agent", "askdb"));
        assertThat(created.approvalId()).isEqualTo("ap-1");
        assertThat(created.status()).isEqualTo("pending");
        assertThat(created.filePath()).isNull();
        var ready = service.onApproved(created.exportId(), "{\"phone\":\"138****5678\"}");
        assertThat(ready.filePath()).isEqualTo("exports/" + created.exportId() + ".json");
        assertThat(ready.body()).contains("138****5678");
    }

    @Test void detachedArchiveStillDetectsAChangedFile() {
        var archive = new ArchiveJob();
        archive.detach("askdb", "2026-10", "{\"event_id\":\"e1\"}");
        assertThat(archive.brokenMonth("askdb", "2026-10")).isEmpty();
        archive.replaceObject("askdb", "2026-10", "{\"event_id\":\"tampered\"}");
        assertThat(archive.brokenMonth("askdb", "2026-10")).contains("2026-10");
    }
}
