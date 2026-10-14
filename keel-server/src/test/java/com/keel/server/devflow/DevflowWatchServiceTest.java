package com.keel.server.devflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DevflowWatchServiceTest {
    @Test
    void productionWatchRequiresTheExactReadyImageAndMergeCommit() {
        var sha = "a".repeat(40);
        var image = "registry.example/employee@sha256:" + "b".repeat(64);
        var deployment = new DeploymentBuilder()
                .withNewMetadata().withName("spec-agent-prod").withGeneration(2L)
                    .addToLabels("keel.io/agent", "spec-agent")
                    .addToLabels("keel.io/env", "prod")
                    .addToLabels("keel.io/commit", sha).endMetadata()
                .withNewSpec().withReplicas(1)
                    .withNewTemplate()
                        .withNewMetadata().addToLabels("keel.io/agent", "spec-agent")
                            .addToLabels("keel.io/env", "prod")
                            .addToLabels("keel.io/commit", sha).endMetadata()
                        .withNewSpec().addNewContainer().withName("spec-agent").withImage(image)
                            .endContainer().endSpec()
                    .endTemplate().endSpec()
                .withNewStatus().withObservedGeneration(2L).withReadyReplicas(1)
                    .withUpdatedReplicas(1).withAvailableReplicas(1).endStatus().build();
        assertThat(DevflowWatchService.deploymentReady(deployment, "spec-agent", sha, image)).isTrue();
        assertThat(DevflowWatchService.deploymentReady(deployment, "spec-agent", "c".repeat(40), image)).isFalse();
        assertThat(DevflowWatchService.deploymentReady(deployment, "spec-agent", sha, image + "-changed")).isFalse();
        deployment.getStatus().setUpdatedReplicas(0);
        assertThat(DevflowWatchService.deploymentReady(deployment, "spec-agent", sha, image)).isFalse();
    }

    @Test
    void sevenDistinctHealthyDaysAreRequired() {
        var observations = new ObjectMapper().createArrayNode();
        for (int day = 1; day <= 6; day++) {
            observations.addObject().put("day", "2026-10-%02d".formatted(day)).put("healthy", true);
        }
        assertThat(DevflowWatchService.sevenHealthyDays(observations)).isFalse();
        observations.addObject().put("day", "2026-10-06").put("healthy", true);
        assertThat(DevflowWatchService.sevenHealthyDays(observations)).isFalse();
        observations.remove(6);
        observations.addObject().put("day", "2026-10-07").put("healthy", false);
        assertThat(DevflowWatchService.sevenHealthyDays(observations)).isFalse();
        observations.addObject().put("day", "2026-10-08").put("healthy", true);
        assertThat(DevflowWatchService.sevenHealthyDays(observations)).isFalse();
        for (int day = 9; day <= 14; day++) {
            observations.addObject().put("day", "2026-10-%02d".formatted(day)).put("healthy", true);
        }
        assertThat(DevflowWatchService.sevenHealthyDays(observations)).isTrue();
        observations.addObject().put("day", "2026-10-16").put("healthy", true);
        assertThat(DevflowWatchService.sevenHealthyDays(observations)).isFalse();
    }
}
