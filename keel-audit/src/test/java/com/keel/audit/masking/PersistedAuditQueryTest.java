package com.keel.audit.masking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.audit.chain.HashChainService;
import com.keel.audit.query.AuditLog;
import com.keel.audit.store.MemoryChainStore;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PersistedAuditQueryTest {
    @Test void freshQueryReadsDurableChainWithEnvironmentAndHashEvidence() throws Exception {
        var store=new MemoryChainStore();
        var chain=new HashChainService(store);
        var json=new ObjectMapper();
        chain.append(json.readTree("""
                {"event_id":"a","agent":"careermate-keel","env":"staging","ts":"2026-10-25T10:00:00Z",
                 "action":"tool.call","risk":"high","decision":"allowed","payload":{}}
                """),true);
        chain.append(json.readTree("""
                {"event_id":"b","agent":"other","env":"prod","ts":"2026-10-25T11:00:00Z",
                 "action":"tool.call","risk":"low","decision":"allowed","payload":{}}
                """),false);
        assertThat(new com.keel.audit.chain.ChainVerifyJob(chain,store).verify("")).isEmpty();
        var query=new AuditLog(store);
        var rows=query.filter("staging","careermate-keel","tool.call","high",null,null,null,1,10);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().path("hashVerified").asBoolean()).isTrue();
        assertThat(rows.getFirst().path("hash").asText()).hasSize(64);
        assertThat(query.total(null,null,null,null,null,null)).isEqualTo(2);
        assertThat(query.filter(null,null,null,null,null,null,1,1).getFirst().path("event_id").asText()).isEqualTo("b");
    }
}
