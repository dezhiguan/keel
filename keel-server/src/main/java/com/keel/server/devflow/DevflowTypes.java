package com.keel.server.devflow;

import java.util.List;

/** JSON shape of contracts/console-api.openapi.yaml devflow schemas. */
public final class DevflowTypes {
    private DevflowTypes() {}

    public record Summary(int active, int queued, int dailyLimit, int waitingHuman, int humanDev,
                          double firstGatePassRate, double withinRoundsPassRate, double spentCny) {}

    public record JobList(Summary summary, List<Job> items) {}

    public record Seed(int human, int agent, int holdout) {}

    public record ToolRef(String name, String risk, String owner) {}

    public record Event(String at, String actor, String summary) {}

    public record Job(String jobId, String title, String layer, String kind, String mode, String status, String stage,
                      String targetAgent, String producerAgent, String template, double spentCny, double budgetCny,
                      int fixRounds, int maxFixRounds, String batchId, boolean needReview, String humanDevUser,
                      Integer watchDay, String requester, String ownerOrg, String goal, List<ToolRef> tools,
                      List<String> knowledge, Seed seed, List<Event> events) {
        static Job create(String jobId, String title, String targetAgent, String mode, String requester, String ownerOrg,
                          String batchId, String status, String goal, double budgetCny, int maxFixRounds, List<Event> events) {
            return new Job(jobId, title, "BIZ", "CREATE", mode, status, "SPEC", targetAgent, "dev-lead", "tool-agent",
                    0, budgetCny, 0, maxFixRounds, batchId, false, null, null, requester, ownerOrg, goal,
                    List.of(), List.of(), new Seed(0, 0, 0), events);
        }

        Job withStatus(String status, String stage, boolean needReview, String humanDevUser, List<Event> events) {
            return new Job(jobId, title, layer, kind, mode, status, stage, targetAgent, producerAgent, template,
                    spentCny, budgetCny, fixRounds, maxFixRounds, batchId, needReview, humanDevUser, watchDay,
                    requester, ownerOrg, goal, tools, knowledge, seed, events);
        }

        Job withEvents(List<Event> events) {
            return new Job(jobId, title, layer, kind, mode, status, stage, targetAgent, producerAgent, template,
                    spentCny, budgetCny, fixRounds, maxFixRounds, batchId, needReview, humanDevUser, watchDay,
                    requester, ownerOrg, goal, tools, knowledge, seed, events);
        }

        Job withProgress(String status, String stage, double spentCny, int fixRounds, List<Event> events) {
            return new Job(jobId, title, layer, kind, mode, status, stage, targetAgent, producerAgent, template,
                    spentCny, budgetCny, fixRounds, maxFixRounds, batchId, needReview, humanDevUser, watchDay,
                    requester, ownerOrg, goal, tools, knowledge, seed, events);
        }

        Job withSeed(Seed seed, List<Event> events) {
            return new Job(jobId, title, layer, kind, mode, status, stage, targetAgent, producerAgent, template,
                    spentCny, budgetCny, fixRounds, maxFixRounds, batchId, needReview, humanDevUser, watchDay,
                    requester, ownerOrg, goal, tools, knowledge, seed, events);
        }
    }

    public record Batch(String batchId, String title, String requester, int concurrency, String pilotJobId,
                        boolean pilotPassed, String createdAt, List<Job> jobs) {}

    public record BatchList(List<Batch> items) {}

    public record PreviewRow(String targetAgent, String title, String mode, String owner, String flag) {}

    public record Preview(List<PreviewRow> rows) {}

    public record BatchCreate(String title, List<PreviewRow> rows) {}

    public record Assist(String instruction) {}

    public record Draft(String title, String targetAgent, String layer, String kind, String mode, String goal,
                        String template, Double dailyBudgetCny, Integer seedCount, List<String> tools,
                        List<String> knowledge, String ownerOrg) {}

    public record Settings(double budgetCny, int maxFixRounds, int holdoutPercent, int minSeed, double keyCapCny,
                           int dailyLimit, int concurrency, List<String> templates) {
        static Settings defaults() {
            return new Settings(80, 3, 30, 30, 50, 3, 3, List.of("tool-agent"));
        }
    }

    public record StageReport(String status, String summary, String traceId, Double costCny, Artifact artifact) {}

    public record Artifact(String kind, String ref, String sha256, String origin) {}

    public record SeedAccept(List<String> acceptedCaseIds) {}

    public record SeedResult(int humanCount, int holdoutCount, int acceptedAgentCount) {}

    public record Step(Job job, boolean exhausted) {}
}
