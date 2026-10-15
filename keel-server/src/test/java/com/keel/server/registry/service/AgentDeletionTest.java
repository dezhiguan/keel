package com.keel.server.registry.service;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.registry.model.entity.Agent;
import com.keel.server.registry.model.enums.AgentStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentDeletionTest {
    @Test void onlineAgentIsRejectedWithoutBeingHidden() {
        var online = agent(AgentStatus.ONLINE);
        assertThatThrownBy(() -> LifecycleService.markRemoved(online))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.AGENT_NOT_RETIRED);
        assertThat(online.isRemoved()).isFalse();
    }

    @Test void retiredAgentIsHiddenAndKeepsItsRetiredStatus() {
        var retired = agent(AgentStatus.RETIRED);
        assertThat(LifecycleService.markRemoved(retired)).isSameAs(retired);
        assertThat(retired.isRemoved()).isTrue();
        assertThat(retired.getStatus()).isEqualTo(AgentStatus.RETIRED);
    }

    @Test void removedNameCannotBeRegisteredAgain() {
        var removed = agent(AgentStatus.RETIRED);
        removed.setRemoved(true);
        assertThatThrownBy(() -> LifecycleService.rejectRemovedName(removed))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.AGENT_NAME_TAKEN);
        assertThatCode(() -> LifecycleService.rejectRemovedName(agent(AgentStatus.RETIRED))).doesNotThrowAnyException();
    }

    private static Agent agent(AgentStatus status) {
        var agent = new Agent();
        agent.setName("qa-agent");
        agent.setKind(Agent.KIND_AGENT);
        agent.setStatus(status);
        return agent;
    }
}
