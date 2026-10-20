package com.keel.server.insight;

import com.keel.server.readmodel.TraceIndexStore;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class OverviewLedgerTest {
    @Test void backgroundAggregatesJoinAnotherInstancesLedgerRefreshInsteadOfReportingAnOutage() throws Exception {
        var snapshots=new OverviewReadModelTest.MemorySnapshots();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        var costs=new CostService(null) {
            @Override public Ledger ledger() {
                calls.incrementAndGet();entered.countDown();
                try {release.await(3,TimeUnit.SECONDS);} catch(InterruptedException e) {throw new IllegalStateException(e);}
                return new Ledger(List.of(),List.of(),List.of(),List.of());
            }
        };
        var index=new TraceIndexStore(null,null,snapshots,new org.springframework.jdbc.datasource.DataSourceTransactionManager()) {
            @Override public boolean replaceBills(String owner,CostService.Ledger value) {
                return snapshots.finish("overview-ledger-v1",owner,value,java.time.Duration.ofSeconds(60));
            }
        };
        var first=new OverviewLedger(snapshots,costs,index);var other=new OverviewLedger(snapshots,costs,index);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(first::get);assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            var b=pool.submit(other::get);
            release.countDown();
            assertThat(a.get(3,TimeUnit.SECONDS)).isEqualTo(b.get(3,TimeUnit.SECONDS));assertThat(calls.get()).isEqualTo(1);
        } finally {release.countDown();pool.shutdownNow();}
    }
}
