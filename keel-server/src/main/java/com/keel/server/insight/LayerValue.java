package com.keel.server.insight;

import java.util.List;

/** Rates for the overview layer cards. A null field means the denominator was empty. */
public final class LayerValue {
    private LayerValue() {}

    public record Biz(Double resolutionPct, Double handoffPct, Double downvotePct, Double ungroundedPct) {}

    public record Dev(Double firstPassPct, Double withinRoundsPct, Double avgDeliveryDays, Double takeoverPct) {}

    public record Meta(Integer produced, Integer upgraded, Double avgGateScore, Double humanPrPct) {}

    public record Layers(Biz biz, Dev dev, Meta meta) {}

    public record JobSample(String status, String stage, String layer, String kind, int fixRounds,
                             boolean human, Boolean holdoutPassed, Double durationDays) {}

    public static Layers empty() {
        return new Layers(new Biz(null, null, null, null), new Dev(null, null, null, null), new Meta(null, null, null, null));
    }

    public static Double percent(int part, int whole) {
        if (whole <= 0) return null;
        return Math.round(part * 1000.0 / whole) / 10.0;
    }

    public static Double average(List<Double> values) {
        if (values == null || values.isEmpty()) return null;
        return Math.round(values.stream().mapToDouble(Double::doubleValue).average().orElseThrow() * 100.0) / 100.0;
    }

    public static Biz biz(int terminal, int resolved, int runs, int handoffs, int feedback, int downvotes) {
        return new Biz(percent(resolved, terminal), percent(handoffs, runs), percent(downvotes, feedback), null);
    }

    public static Dev dev(List<JobSample> jobs) {
        int gate = 0;
        int first = 0;
        int finished = 0;
        int within = 0;
        int taken = 0;
        double days = 0;
        int delivered = 0;
        for (var job : jobs) {
            if (job.human()) taken++;
            boolean reached = job.holdoutPassed() != null || "DONE".equals(job.status()) || "FAIL".equals(job.status())
                    || job.fixRounds() > 0;
            if (reached) {
                gate++;
                boolean passed = Boolean.TRUE.equals(job.holdoutPassed())
                        || (job.holdoutPassed() == null && "DONE".equals(job.status()));
                if (job.fixRounds() == 0 && passed) first++;
            }
            if ("DONE".equals(job.status()) || "FAIL".equals(job.status())) {
                finished++;
                if ("DONE".equals(job.status()) && job.fixRounds() < 3) within++;
            }
            if ("DONE".equals(job.status()) && job.durationDays() != null) {
                days += job.durationDays();
                delivered++;
            }
        }
        Double cycle = delivered == 0 ? null : Math.round(days / delivered * 10.0) / 10.0;
        return new Dev(percent(first, gate), percent(within, finished), cycle, percent(taken, jobs.size()));
    }

    public static Meta meta(List<JobSample> jobs, List<Double> scores, int humanCode, int code) {
        int produced = 0;
        int upgraded = 0;
        boolean saw = false;
        for (var job : jobs) {
            if (!"DEV".equals(job.layer())) continue;
            saw = true;
            if (!"DONE".equals(job.status())) continue;
            if ("CREATE".equals(job.kind())) produced++;
            if ("CHANGE".equals(job.kind())) upgraded++;
        }
        return new Meta(saw ? produced : null, saw ? upgraded : null, average(scores), percent(humanCode, code));
    }
}
