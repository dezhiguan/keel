package com.keel.audit;

import com.keel.audit.chain.ChainStore;
import com.keel.audit.chain.ChainVerifyJob;
import com.keel.audit.chain.HashChainService;
import com.keel.audit.ingest.AuditMqConsumer;
import com.keel.audit.query.AuditLog;
import com.keel.audit.query.ExportService;
import com.keel.audit.store.MemoryChainStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuditConfiguration {
    @Bean
    MemoryChainStore memoryChainStore() {
        return new MemoryChainStore();
    }

    @Bean
    HashChainService hashChainService(MemoryChainStore store) {
        return new HashChainService(store);
    }

    @Bean
    AuditMqConsumer auditMqConsumer(HashChainService chain) {
        return new AuditMqConsumer(chain);
    }

    @Bean
    ChainVerifyJob chainVerifyJob(HashChainService chain, MemoryChainStore store) {
        return new ChainVerifyJob(chain, store);
    }

    @Bean
    ChainStore chainStore(MemoryChainStore store) {
        return store;
    }

    @Bean
    AuditLog auditLog() {
        return new AuditLog();
    }

    @Bean
    ExportService exportService() {
        return new ExportService((subject, filter) -> "approval-pending");
    }
}
