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
        return chain.brokenAt(store.list(agent));
    }
}
