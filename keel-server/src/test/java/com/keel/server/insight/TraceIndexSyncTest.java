package com.keel.server.insight;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class TraceIndexSyncTest {
    @Test void checkpointFollowsAllPagesThenSwitchesToChildrenBeforeAdvancingCoverage() {
        var now=Instant.parse("2026-10-20T08:00:00Z");
        var point=new TraceIndexSync.Checkpoint(now.minus(Duration.ofDays(30)),now,true,null,null,null,null);
        var next=TraceIndexSync.advance(point,"opaque cursor",now);
        assertThat(next.from()).isEqualTo(point.from());assertThat(next.to()).isEqualTo(point.to());assertThat(next.completedTo()).isNull();
        next=TraceIndexSync.advance(next,"",now);assertThat(next.roots()).isFalse();assertThat(next.cursor()).isNull();assertThat(next.coveredFrom()).isNull();
        next=TraceIndexSync.advance(next,"child-next",now);assertThat(next.completedTo()).isNull();
        next=TraceIndexSync.advance(next,"",now);assertThat(next.completedTo()).isEqualTo(now);assertThat(next.coveredFrom()).isEqualTo(point.from());
        assertThat(next.from()).isEqualTo(now.minus(Duration.ofHours(1)));assertThat(next.roots()).isTrue();
    }
    @Test void periodicReconciliationRetainsOlderCoverageAndRescansDelayedSpans() {
        var now=Instant.parse("2026-10-20T08:00:00Z");
        var point=new TraceIndexSync.Checkpoint(now.minusSeconds(3600),now,false,null,now.minus(Duration.ofDays(30)),now.minusSeconds(60),now.minusSeconds(1900));
        var next=TraceIndexSync.advance(point,"",now);
        assertThat(next.from()).isEqualTo(now.minus(Duration.ofDays(7)));assertThat(next.coveredFrom()).isEqualTo(point.coveredFrom());
    }
}
