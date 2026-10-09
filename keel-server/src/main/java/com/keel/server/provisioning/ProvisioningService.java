package com.keel.server.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.integration.audit.ConfigAudit;
import com.keel.server.registry.service.SelfCheckService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ProvisioningService {
    private final List<ProvisionStep> steps;
    private final ResourceLedger ledger;
    private final Checker checker;
    private final ConfigAudit audit;

    @Autowired
    public ProvisioningService(AuthClientProvisioner auth, LiteLlmProvisioner litellm, SecretWriter secrets,
                               LangfuseProvisioner datasets, ResourceLedger ledger, SelfCheckService checks,
                               ConfigAudit audit) {
        this(List.of(auth, litellm, secrets, datasets), ledger,
                (endpoint, manifest) -> checks.check(endpoint, false, checks.approvalBound(manifest)), audit);
    }

    public ProvisioningService(List<ProvisionStep> steps, ResourceLedger ledger, Checker checker, ConfigAudit audit) {
        this.steps = List.copyOf(steps);
        this.ledger = ledger;
        this.checker = checker;
        this.audit = audit;
    }

    public SelfCheckService.Report register(String agent, String env, String endpoint, JsonNode manifest) {
        var done = new ArrayList<ProvisionStep>();
        try {
            for (var step : steps) {
                step.provision(agent, env, manifest);
                ledger.activate(agent, env, step.resourceType(), agent);
                done.add(step);
            }
        } catch (RuntimeException e) {
            rollback(agent, env, done);
            throw e;
        }
        var report = checker.check(endpoint, manifest);
        if (!report.passed()) {
            return report;
        }
        try {
            audit.record(agent, env);
        } catch (RuntimeException e) {
            rollback(agent, env, done);
            throw e;
        }
        return report;
    }

    /**
     * Revoke every still-active resource for this environment, last provisioned first.
     * A step that was never activated is skipped. Revoke failure stays on the ledger and stops the retire.
     */
    public void retire(String agent, String env) {
        var active = new java.util.LinkedHashSet<>(ledger.activeTypes(agent, env));
        var done = new ArrayList<ProvisionStep>();
        for (var i = steps.size() - 1; i >= 0; i--) {
            var step = steps.get(i);
            if (active.remove(step.resourceType())) {
                done.add(step);
            }
        }
        for (var type : active) {
            ledger.mark(agent, env, type, "REVOKED");
        }
        revokeAll(agent, env, done);
    }

    private void rollback(String agent, String env, List<ProvisionStep> done) {
        var reverse = new ArrayList<ProvisionStep>();
        for (var i = done.size() - 1; i >= 0; i--) {
            reverse.add(done.get(i));
        }
        revokeAll(agent, env, reverse);
    }

    private void revokeAll(String agent, String env, List<ProvisionStep> done) {
        for (var step : done) {
            try {
                step.revoke(agent, env);
                ledger.mark(agent, env, step.resourceType(), "REVOKED");
            } catch (RuntimeException e) {
                ledger.mark(agent, env, step.resourceType(), "REVOKE_FAILED");
                throw e;
            }
        }
    }

    public interface Checker {
        SelfCheckService.Report check(String endpoint, JsonNode manifest);
    }
}
