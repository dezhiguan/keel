package com.keel.server.insight;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LayerValueTest {
    @Test void bizRatesStayEmptyWhenNothingHappened() {
        assertThat(LayerValue.biz(0, 0, 0, 0, 0, 0)).isEqualTo(new LayerValue.Biz(null, null, null, null));
    }

    @Test void oneDownvoteInTenFeedbackIsTenPercent() {
        var biz = LayerValue.biz(4, 3, 5, 1, 10, 1);
        assertThat(biz.resolutionPct()).isEqualTo(75.0);
        assertThat(biz.handoffPct()).isEqualTo(20.0);
        assertThat(biz.downvotePct()).isEqualTo(10.0);
        assertThat(biz.ungroundedPct()).isNull();
    }

    @Test void firstPassIgnoresLaterRepairsAndWithinThreeCountsDoneJobs() {
        var jobs = List.of(
                job("DONE", "WATCH", "BIZ", "CREATE", 0, false, true, 2.0),
                job("DONE", "WATCH", "BIZ", "CREATE", 2, true, true, 3.2),
                job("FAIL", "GATE", "BIZ", "CREATE", 3, false, false, null),
                job("RUN", "BUILD", "BIZ", "CREATE", 0, false, null, null));
        var dev = LayerValue.dev(jobs);
        assertThat(dev.firstPassPct()).isEqualTo(33.3);
        assertThat(dev.withinRoundsPct()).isEqualTo(66.7);
        assertThat(dev.avgDeliveryDays()).isEqualTo(2.6);
        assertThat(dev.takeoverPct()).isEqualTo(25.0);
    }

    @Test void metaCountsStayBlankUntilADevJobExists() {
        var bizOnly = List.of(job("DONE", "WATCH", "BIZ", "CREATE", 0, false, true, 1.0));
        assertThat(LayerValue.meta(bizOnly, List.of(), 0, 0).produced()).isNull();
        assertThat(LayerValue.meta(bizOnly, List.of(), 0, 0).upgraded()).isNull();

        var devJobs = List.of(
                job("DONE", "WATCH", "DEV", "CREATE", 0, false, true, 1.0),
                job("DONE", "WATCH", "DEV", "CHANGE", 1, true, true, 1.0),
                job("RUN", "BUILD", "DEV", "CREATE", 0, false, null, null));
        var meta = LayerValue.meta(devJobs, List.of(0.8, 0.9), 1, 4);
        assertThat(meta.produced()).isEqualTo(1);
        assertThat(meta.upgraded()).isEqualTo(1);
        assertThat(meta.avgGateScore()).isEqualTo(0.85);
        assertThat(meta.humanPrPct()).isEqualTo(25.0);
    }

    private static LayerValue.JobSample job(String status, String stage, String layer, String kind, int rounds,
                                             boolean human, Boolean passed, Double days) {
        return new LayerValue.JobSample(status, stage, layer, kind, rounds, human, passed, days);
    }
}
