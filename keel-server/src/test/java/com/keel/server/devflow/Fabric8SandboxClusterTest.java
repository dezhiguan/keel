package com.keel.server.devflow;

import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateWaiting;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Fabric8SandboxClusterTest {
    @Test
    void reportsTheContainerWaitingReasonWithoutMultilineNoise() {
        var waiting = new ContainerStateWaiting();
        waiting.setReason("ImagePullBackOff");
        waiting.setMessage("registry refused\nimage pull");
        var state = new ContainerState();
        state.setWaiting(waiting);
        var container = new ContainerStatus();
        container.setName("runner");
        container.setState(state);
        var status = new PodStatus();
        status.setContainerStatuses(List.of(container));
        var pod = new Pod();
        pod.setStatus(status);

        assertThat(Fabric8SandboxCluster.podDiagnosis(pod))
                .isEqualTo("容器 runner 等待：ImagePullBackOff registry refused image pull");
    }

    @Test
    void prefersAnImagePullFailureOverAnotherContainersNormalStartup() {
        var starting = waiting("fake-llm", "ContainerCreating");
        var blocked = waiting("runner", "ImagePullBackOff");
        var status = new PodStatus();
        status.setContainerStatuses(List.of(starting, blocked));
        var pod = new Pod();
        pod.setStatus(status);

        assertThat(Fabric8SandboxCluster.podDiagnosis(pod)).contains("runner", "ImagePullBackOff");
    }

    private static ContainerStatus waiting(String name, String reason) {
        var waiting = new ContainerStateWaiting();
        waiting.setReason(reason);
        var state = new ContainerState();
        state.setWaiting(waiting);
        var container = new ContainerStatus();
        container.setName(name);
        container.setState(state);
        return container;
    }
}
