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
            // Do not republish an old ledger as a newly successful overview.
            throw new IllegalStateException("Gateway ledger refresh is pending");
        }
        try {
            var ledger=costs.ledger();
            if(!index.replaceBills(owner,ledger)) throw new IllegalStateException("Ledger lease expired");
            return ledger;
        } catch(RuntimeException error) {snapshots.fail("overview-ledger-v1",owner,Duration.ofSeconds(30));throw error;}
    }
}
