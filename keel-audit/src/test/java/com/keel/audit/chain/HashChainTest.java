package com.keel.audit.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.audit.ingest.AuditMqConsumer;
import com.keel.audit.store.MemoryChainStore;
import com.keel.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HashChainTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void canonicalRules() throws Exception {
        assertThat(CanonicalHash.canonical(json.readTree("{\"b\":1,\"a\":2,\"hash\":\"abc\",\"n\":null}")))
                .isEqualTo("{\"a\":2,\"b\":1}");
        assertThat(CanonicalHash.canonical(json.readTree("{\"ts\":\"2026-09-29T12:49:30.100Z\",\"agent\":\"甲\"}")))
                .isEqualTo("{\"agent\":\"甲\",\"ts\":\"2026-09-29T12:49:30.1Z\"}");
        assertThat(CanonicalHash.canonical(json.readTree("{\"ts\":\"2026-09-29T12:49:30.000Z\"}")))
                .isEqualTo("{\"ts\":\"2026-09-29T12:49:30Z\"}");
        assertThat(CanonicalHash.canonical(json.readTree("{\"ts\":\"2026-09-29T12:49:30Z\",\"items\":[1,true]}")))
                .isEqualTo("{\"items\":[1,true],\"ts\":\"2026-09-29T12:49:30Z\"}");
        assertThat(CanonicalHash.canonical(json.readTree("{\"payload\":{\"ts\":\"2026-09-29T12:49:30.100Z\"}}")))
                .contains("2026-09-29T12:49:30.100Z");
        assertThatThrownBy(() -> CanonicalHash.canonical(json.readTree("{\"amount\":1.5}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalHash.canonical(json.readTree("1.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("/");
        assertThat(CanonicalHash.sha256("{}", null)).hasSize(64);
        assertThat(CanonicalHash.sha256(json.readTree("{\"agent\":\"a\",\"event_id\":\"e\"}"))).hasSize(64);
        assertThat(CanonicalHash.sha256(json.readTree("{\"agent\":\"a\",\"prev_hash\":null}"))).hasSize(64);
        assertThat(CanonicalHash.sha256(json.readTree("{\"agent\":\"a\",\"prev_hash\":\"abc\"}"))).hasSize(64);
        assertThatThrownBy(() -> CanonicalHash.canonical(json.readTree("{\"payload\":{\"n\":1.2}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/payload");
        assertThat(CanonicalHash.canonical(json.readTree("{\"ts\":\"2026-09-29T12:49:30+00:00\"}")))
                .contains("2026-09-29T12:49:30+00:00");
        assertThat(CanonicalHash.canonical(json.readTree("{\"ts\":1}"))).isEqualTo("{\"ts\":1}");
    }

    @Test void concurrentAppendsFormOneChain() throws Exception {
        var store = new MemoryChainStore();
        var chain = new HashChainService(store);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                int id = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(2, TimeUnit.SECONDS);
                        chain.append(json.readTree("{\"event_id\":\"e" + id + "\",\"agent\":\"askdb\",\"risk\":\"low\"}"), false);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        }
        var rows = store.list("askdb");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).prevHash()).isEmpty();
        assertThat(rows.get(1).prevHash()).isEqualTo(rows.get(0).hash());
        assertThat(store.head("askdb").count()).isEqualTo(2);
        assertThat(new ChainVerifyJob(chain, store).verify("askdb")).isEmpty();
    }

    @Test void tamperPointsAtTheEvent() throws Exception {
        var store = new MemoryChainStore();
        var chain = new HashChainService(store);
        chain.append(json.readTree("{\"event_id\":\"e1\",\"agent\":\"askdb\"}"), false);
        chain.append(json.readTree("{\"event_id\":\"e2\",\"agent\":\"askdb\"}"), false);
        var broken = List.of(
                store.list("askdb").get(0),
                new ChainStore.Link("e2", "askdb", store.list("askdb").get(1).prevHash(), store.list("askdb").get(1).hash(), "{\"tampered\":true}"));
        assertThat(chain.brokenAt(broken)).contains("e2");
        assertThat(chain.brokenAt(List.of())).isEmpty();
        assertThat(chain.brokenAt(List.of(new ChainStore.Link("e9", "askdb", "ffff", "abcd", "{}")))).contains("e9");
        assertThat(chain.brokenAt(List.of(new ChainStore.Link("e8", "askdb", null, "abcd", "{}")))).contains("e8");
    }

    @Test void syncFailureDoesNotAdvanceTheHead() throws Exception {
        var store = new MemoryChainStore();
        store.failNextInsert();
        var chain = new HashChainService(store);
        assertThatThrownBy(() -> chain.append(json.readTree("{\"event_id\":\"e1\",\"agent\":\"askdb\",\"risk\":\"high\"}"), true))
                .isInstanceOf(HashChainService.AuditAppendException.class)
                .extracting(error -> ((HashChainService.AuditAppendException) error).code())
                .isEqualTo(ErrorCode.AUDIT_WRITE_FAILED);
        assertThat(store.head("askdb")).isNull();
        assertThat(store.list("askdb")).isEmpty();
        store.failNextInsert();
        assertThatThrownBy(() -> chain.append(json.readTree("{\"event_id\":\"e1\",\"agent\":\"askdb\"}"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("insert failed");
        assertThatThrownBy(() -> chain.append(json.readTree("{\"event_id\":\"e1\"}"), true))
                .isInstanceOf(HashChainService.AuditAppendException.class);
        assertThatThrownBy(() -> chain.append(json.readTree("{\"event_id\":\"e1\"}"), false))
                .isInstanceOf(IllegalArgumentException.class);
        store.failNextInsertAsAudit();
        assertThatThrownBy(() -> chain.append(json.readTree("{\"event_id\":\"e1\",\"agent\":\"askdb\"}"), true))
                .isInstanceOf(HashChainService.AuditAppendException.class);
    }

    @Test void sameAgentAsyncEventsStayInSendOrder() throws Exception {
        var store = new MemoryChainStore();
        var chain = new HashChainService(store);
        var consumer = new AuditMqConsumer(chain);
        consumer.onMessage(json.readTree("{\"event_id\":\"first\",\"agent\":\"askdb\"}"));
        consumer.onMessage(json.readTree("{\"event_id\":\"second\",\"agent\":\"askdb\"}"));
        assertThat(store.list("askdb")).extracting(ChainStore.Link::eventId).containsExactly("first", "second");
        assertThat(store.list("askdb").get(1).prevHash()).isEqualTo(store.list("askdb").get(0).hash());
    }

    @Test void partitionNameUsesTheEventMonth() {
        assertThat(com.keel.audit.store.AuditEventMapper.partitionName(java.time.Instant.parse("2026-10-04T03:00:00Z")))
                .isEqualTo("audit_event_2026_10");
        assertThatThrownBy(() -> com.keel.audit.store.AuditEventMapper.partitionName(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
