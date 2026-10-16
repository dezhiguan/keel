package com.keel.server.devflow;

import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateWaiting;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Fabric8SandboxClusterTest {
    @Test
    void usesRunnerExitEvenWhenFakeServicesAreStillRunning() {
        assertThat(Fabric8SandboxCluster.runnerPhase(podWithRunnerExit(0)))
                .isEqualTo(SandboxCluster.Phase.SUCCEEDED);
        assertThat(Fabric8SandboxCluster.runnerPhase(podWithRunnerExit(1)))
                .isEqualTo(SandboxCluster.Phase.FAILED);
    }

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

    private static Pod podWithRunnerExit(int code) {
        var terminated = new ContainerStateTerminated();
        terminated.setExitCode(code);
        var state = new ContainerState();
        state.setTerminated(terminated);
        var runner = new ContainerStatus();
        runner.setName("runner");
        runner.setState(state);
        var fake = new ContainerStatus();
        fake.setName("fake-llm");
        var status = new PodStatus();
        status.setContainerStatuses(List.of(fake, runner));
        var pod = new Pod();
        pod.setStatus(status);
        return pod;
    }
}
