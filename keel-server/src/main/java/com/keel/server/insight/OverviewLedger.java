package com.keel.server.insight;

import com.keel.server.readmodel.InsightSnapshots;
import com.keel.server.readmodel.TraceIndexStore;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;

/** One shared gateway ledger for all overview environments and time ranges, loaded off the request path. */
@Component
public class OverviewLedger {
    private final InsightSnapshots snapshots;
    private final CostService costs;
    private final TraceIndexStore index;
    public OverviewLedger(InsightSnapshots snapshots,CostService costs,TraceIndexStore index) {this.snapshots=snapshots;this.costs=costs;this.index=index;}
    public CostService.Ledger get() {
        var snap=snapshots.read("overview-ledger-v1");
        if(snap.payload()!=null && snap.at().plusSeconds(60).isAfter(Instant.now())) return snapshots.decode(snap.payload(),CostService.Ledger.class);
        var owner=snapshots.claim("overview-ledger-v1",Duration.ofSeconds(120));
        if(owner==null) {
            // Only background workers call this method. Join the shared refresh instead of
            // turning another instance's successful in-flight load into a false outage.
            var deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
            while(System.nanoTime()<deadline) {
                var joined=snapshots.read("overview-ledger-v1");
                if(joined.payload()!=null && joined.at().plusSeconds(60).isAfter(Instant.now()))
                    return snapshots.decode(joined.payload(),CostService.Ledger.class);
                try { Thread.sleep(250); }
                catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IllegalStateException("Ledger refresh interrupted",error);}
            }
            throw new IllegalStateException("Gateway ledger refresh is pending");
        }
        try {
            var ledger=costs.ledger();
            if(!index.replaceBills(owner,ledger)) throw new IllegalStateException("Ledger lease expired");
            return ledger;
        } catch(RuntimeException error) {snapshots.fail("overview-ledger-v1",owner,Duration.ofSeconds(30));throw error;}
    }
}
