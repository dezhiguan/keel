package com.keel.audit.store;

import com.keel.audit.chain.ChainStore;
import com.keel.audit.chain.HashChainService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** In-process chain. The same agent lock is held across insert and head advance. */
public class MemoryChainStore implements ChainStore {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Head> heads = new ConcurrentHashMap<>();
    private final List<Link> rows = new ArrayList<>();
    private final ThreadLocal<Link> pending = new ThreadLocal<>();
    private boolean failInsert;
    private boolean failAsAudit;

    public void failNextInsert() {
        this.failInsert = true;
    }

    public void failNextInsertAsAudit() {
        this.failInsert = true;
        this.failAsAudit = true;
    }

    @Override
    public void lock(String agent) {
        locks.computeIfAbsent(agent, key -> new ReentrantLock()).lock();
    }

    @Override
    public Head head(String agent) {
        return heads.get(agent);
    }

    @Override
    public void insert(Link link) {
        if (failInsert) {
            failInsert = false;
            if (failAsAudit) {
                failAsAudit = false;
                throw new HashChainService.AuditAppendException(new IllegalStateException("insert failed"));
            }
            throw new IllegalStateException("insert failed");
        }
        rows.add(link);
        pending.set(link);
    }

    @Override
    public void advance(String agent, String hash, long count) {
        heads.put(agent, new Head(hash, count));
        pending.remove();
    }

    @Override
    public void rollback(String agent) {
        Link link = pending.get();
        if (link != null) {
            rows.remove(link);
            pending.remove();
        }
    }

    @Override
    public void unlock(String agent) {
        ReentrantLock lock = locks.get(agent);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    @Override
    public List<Link> list(String agent) {
        return rows.stream().filter(row -> row.agent().equals(agent)).toList();
    }
}
