package com.keel.server.provisioning;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.keel.server.integration.audit.ConfigAudit;
import com.keel.server.registry.service.SelfCheckService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProvisioningRollbackTest {
    @Test void eachFailedStepRevokesEarlierStepsInReverse() {
        for (var failAt = 0; failAt < 4; failAt++) {
            var calls = new ArrayList<String>();
            var ledger = new MemoryLedger();
            var service = service(calls, failAt, false, ledger, passed(), (agent, env) -> { });
            assertThatThrownBy(() -> service.register("askdb", "dev", "http://askdb", JsonNodeFactory.instance.objectNode()))
                    .isInstanceOf(IllegalStateException.class);
            var expected = new ArrayList<String>();
            for (var i = failAt - 1; i >= 0; i--) {
                expected.add("revoke:" + TYPES[i]);
            }
            assertThat(calls).endsWith(expected.toArray(String[]::new));
            assertThat(ledger.active).isEmpty();
        }
    }

    @Test void failedSelfCheckDoesNotRollBack() {
        var calls = new ArrayList<String>();
        var ledger = new MemoryLedger();
        var report = new SelfCheckService.Report(false, List.of(new SelfCheckService.Item("健康", false, "down")));
        var service = service(calls, -1, false, ledger, report, (agent, env) -> calls.add("audit"));
        var returned = service.register("askdb", "dev", "http://askdb", JsonNodeFactory.instance.objectNode());
        assertThat(returned.passed()).isFalse();
        assertThat(calls).doesNotContain("revoke:oauth_client", "audit");
        assertThat(ledger.active).containsExactly("oauth_client", "litellm_key", "secret", "dataset");
    }

    @Test void failedAuditRollsBackEveryActiveResource() {
        var calls = new ArrayList<String>();
        var ledger = new MemoryLedger();
        var service = service(calls, -1, false, ledger, passed(), (agent, env) -> {
            throw new IllegalStateException("audit");
        });
        assertThatThrownBy(() -> service.register("askdb", "dev", "http://askdb", JsonNodeFactory.instance.objectNode()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(calls).containsExactly(
                "provision:oauth_client", "provision:litellm_key", "provision:secret", "provision:dataset",
                "revoke:dataset", "revoke:secret", "revoke:litellm_key", "revoke:oauth_client");
        assertThat(ledger.active).isEmpty();
    }

    @Test void retryAfterRollbackProvisionsTheFirstStepAgain() {
        var calls = new ArrayList<String>();
        var ledger = new MemoryLedger();
        var failing = service(calls, 1, false, ledger, passed(), (agent, env) -> { });
        assertThatThrownBy(() -> failing.register("askdb", "dev", "http://askdb", JsonNodeFactory.instance.objectNode()));
        var again = service(calls, -1, false, ledger, passed(), (agent, env) -> { });
        var report = again.register("askdb", "dev", "http://askdb", JsonNodeFactory.instance.objectNode());
        assertThat(report.passed()).isTrue();
        assertThat(calls.stream().filter(call -> call.equals("provision:oauth_client")).count()).isEqualTo(2);
        assertThat(ledger.active).hasSize(4);
    }

    private static final String[] TYPES = {"oauth_client", "litellm_key", "secret", "dataset"};

    private static SelfCheckService.Report passed() {
        return new SelfCheckService.Report(true, List.of());
    }

    private static ProvisioningService service(List<String> calls, int failAt, boolean failRevoke, MemoryLedger ledger,
                                               SelfCheckService.Report report, ConfigAudit audit) {
        var steps = new ArrayList<ProvisionStep>();
        for (var i = 0; i < TYPES.length; i++) {
            var type = TYPES[i];
            var index = i;
            steps.add(new ProvisionStep() {
                public String resourceType() { return type; }
                public void provision(String agent, String env, com.fasterxml.jackson.databind.JsonNode manifest) {
                    calls.add("provision:" + type);
                    if (index == failAt) {
                        throw new IllegalStateException(type);
                    }
                }
                public void revoke(String agent, String env) {
                    calls.add("revoke:" + type);
                    if (failRevoke) {
                        throw new IllegalStateException("revoke");
                    }
                }
            });
        }
        return new ProvisioningService(steps, ledger, (endpoint, manifest) -> report, audit);
    }

    private static final class MemoryLedger implements ResourceLedger {
        private final Map<String, String> rows = new HashMap<>();
        private final List<String> active = new ArrayList<>();

        public void activate(String agent, String env, String type, String externalId) {
            rows.put(type, "ACTIVE");
            if (!active.contains(type)) {
                active.add(type);
            }
        }

        public void mark(String agent, String env, String type, String status) {
            rows.put(type, status);
            active.remove(type);
        }

        public boolean hasActive(String agent, String env) {
            return !active.isEmpty();
        }
    }
}
