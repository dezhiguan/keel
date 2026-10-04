package com.keel.audit.chain;

import java.util.List;

public interface ChainStore {
    void lock(String agent);

    Head head(String agent);

    void insert(Link link);

    void advance(String agent, String hash, long count);

    void rollback(String agent);

    void unlock(String agent);

    List<Link> list(String agent);

    record Head(String hash, long count) {}

    record Link(String eventId, String agent, String prevHash, String hash, String canonicalJson) {}
}
