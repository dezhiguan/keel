package com.keel.audit.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.common.error.ErrorCode;

import java.util.Optional;

/** One append path for sync and async writes. The agent lock is held before insert. */
public class HashChainService {
    private final ChainStore store;

    public HashChainService(ChainStore store) {
        this.store = store;
    }

    public ChainStore.Link append(JsonNode event, boolean sync) {
        String agent = event.path("agent").asText("");
        if (agent.isBlank()) {
            throw fail(sync, new IllegalArgumentException("agent 缺失"));
        }
        store.lock(agent);
        try {
            ChainStore.Head head = store.head(agent);
            String prev = head == null ? "" : head.hash();
            ObjectNode body = event.deepCopy();
            body.put("prev_hash", prev);
            String canonical = CanonicalHash.canonical(body);
            String hash = CanonicalHash.sha256(canonical, prev);
            var link = new ChainStore.Link(body.path("event_id").asText(""), agent, prev, hash, canonical);
            store.insert(link);
            store.advance(agent, hash, (head == null ? 0 : head.count()) + 1);
            return link;
        } catch (RuntimeException e) {
            store.rollback(agent);
            throw fail(sync, e);
        } finally {
            store.unlock(agent);
        }
    }

    private RuntimeException fail(boolean sync, RuntimeException cause) {
        if (cause instanceof AuditAppendException audit) {
            return audit;
        }
        if (sync) {
            return new AuditAppendException(cause);
        }
        return cause;
    }

    public static final class AuditAppendException extends RuntimeException {
        public AuditAppendException(Throwable cause) {
            super(ErrorCode.AUDIT_WRITE_FAILED.message(), cause);
        }

        public ErrorCode code() {
            return ErrorCode.AUDIT_WRITE_FAILED;
        }
    }

    public Optional<String> brokenAt(java.util.List<ChainStore.Link> links) {
        String prev = "";
        for (ChainStore.Link link : links) {
            String expectedPrev = link.prevHash() == null ? "" : link.prevHash();
            if (!prev.equals(expectedPrev)) {
                return Optional.of(link.eventId());
            }
            String again;
            try {
                again = CanonicalHash.sha256(link.canonicalJson(), expectedPrev);
            } catch (IllegalArgumentException invalid) {
                return Optional.of(link.eventId());
            }
            if (!again.equals(link.hash())) {
                return Optional.of(link.eventId());
            }
            prev = link.hash();
        }
        return Optional.empty();
    }
}
