package com.keel.server.devflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxActivityTest {

    @Test void countsOnlyQueuedAndRunning() {
        var store = new MemorySandboxStore();
        var now = Instant.parse("2026-10-16T00:00:00Z");
        store.insert(run("SB-0001", "RUNNING", now));
        store.insert(run("SB-0002", "RUNNING", now.plusSeconds(1)));
        store.insert(run("SB-0003", "QUEUED", now.plusSeconds(2)));
        store.insert(run("SB-0004", "DONE", now.plusSeconds(3)));
        var service = new SandboxService(null, store, null);
        assertThat(service.activity()).isEqualTo(new SandboxService.Activity(2, 1));
    }

    private static SandboxRecord run(String id, String status, Instant created) {
        return SandboxRecord.create(id, "DF-0001", "refund-explainer", "a".repeat(40), status, "meta-agent",
                created, created.plusSeconds(600));
    }
}
