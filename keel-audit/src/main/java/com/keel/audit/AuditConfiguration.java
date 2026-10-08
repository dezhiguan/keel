package com.keel.audit;

import com.keel.audit.chain.ChainStore;
import com.keel.audit.chain.ChainVerifyJob;
import com.keel.audit.chain.HashChainService;
import com.keel.audit.ingest.AuditMqConsumer;
import com.keel.audit.query.AuditLog;
import com.keel.audit.query.ExportService;
import com.keel.audit.store.MemoryChainStore;
import com.keel.audit.store.PostgresChainStore;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class AuditConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "keel.audit", name = "jdbc-url")
    DataSource auditDataSource(@Value("${keel.audit.jdbc-url}") String url,
                               @Value("${keel.audit.jdbc-user:}") String user,
                               @Value("${keel.audit.jdbc-password:}") String password) {
        var source = new HikariDataSource();
        source.setJdbcUrl(url);
        source.setUsername(user);
        source.setPassword(password);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        return source;
    }

    @Bean
    @ConditionalOnProperty(prefix = "keel.audit", name = "jdbc-url")
    ChainStore postgresChainStore(DataSource auditDataSource) {
        return new PostgresChainStore(auditDataSource);
    }

    @Bean
    @ConditionalOnMissingBean(ChainStore.class)
    ChainStore memoryChainStore() {
        return new MemoryChainStore();
    }

    @Bean
    HashChainService hashChainService(ChainStore store) {
        return new HashChainService(store);
    }

    @Bean
    AuditMqConsumer auditMqConsumer(HashChainService chain) {
        return new AuditMqConsumer(chain);
    }

    @Bean
    ChainVerifyJob chainVerifyJob(HashChainService chain, ChainStore store) {
        return new ChainVerifyJob(chain, store);
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
