package com.keel.audit.chain;

import java.util.Optional;

public class ChainVerifyJob {
    private final HashChainService chain;
    private final ChainStore store;

    public ChainVerifyJob(HashChainService chain, ChainStore store) {
        this.chain = chain;
        this.store = store;
    }

    public Optional<String> verify(String agent) {
        var groups = store.list(agent).stream().collect(java.util.stream.Collectors.groupingBy(ChainStore.Link::agent));
        for (var links : groups.values()) {
            var broken = chain.brokenAt(links);
            if (broken.isPresent()) return broken;
        }
        return Optional.empty();
    }
}
