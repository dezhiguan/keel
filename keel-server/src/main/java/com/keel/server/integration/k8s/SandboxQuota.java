package com.keel.server.integration.k8s;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** Reads the sandbox ResourceQuota. A miss stays unknown; only a real quota becomes usage text. */
public final class SandboxQuota {
    static final String NAMESPACE = "keel-devflow-sandbox";
    static final String NAME = "sandbox";
    private static final BigDecimal GI = BigDecimal.valueOf(1024L).pow(3);

    private SandboxQuota() {
    }

    public record Reading(String status, String usage) {
    }

    public static Reading read(KubernetesClient client, int running, int queued, boolean inCluster) {
        if (!inCluster || client == null) {
            return null;
        }
        try {
            var quota = client.resourceQuotas().inNamespace(NAMESPACE).withName(NAME).get();
            if (quota == null) {
                return new Reading("OFFLINE", null);
            }
            Map<String, Quantity> hard = hardOf(quota.getStatus() == null ? null : quota.getStatus().getHard(),
                    quota.getSpec() == null ? null : quota.getSpec().getHard());
            Map<String, Quantity> used = quota.getStatus() != null && quota.getStatus().getUsed() != null
                    ? quota.getStatus().getUsed() : Map.of();
            if (hard.get("requests.cpu") == null || hard.get("requests.memory") == null) {
                return new Reading("OFFLINE", null);
            }
            return new Reading(statusOf(used, hard, queued), usageOf(used, hard, running, queued));
        } catch (KubernetesClientException e) {
            if (e.getCode() == 404) {
                return new Reading("OFFLINE", null);
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String usageOf(Map<String, Quantity> used, Map<String, Quantity> hard, int running, int queued) {
        return number(cores(used.get("requests.cpu"))) + " / " + number(cores(hard.get("requests.cpu")))
                + " 核 · " + number(gibibytes(used.get("requests.memory"))) + " / "
                + number(gibibytes(hard.get("requests.memory"))) + "Gi · 运行 " + running + " · 排队 " + queued;
    }

    static String statusOf(Map<String, Quantity> used, Map<String, Quantity> hard, int queued) {
        if (queued > 0
                || saturated(used.get("requests.cpu"), hard.get("requests.cpu"))
                || saturated(used.get("requests.memory"), hard.get("requests.memory"))
                || saturated(used.get("pods"), hard.get("pods"))) {
            return "DEGRADED";
        }
        return "ONLINE";
    }

    private static Map<String, Quantity> hardOf(Map<String, Quantity> statusHard, Map<String, Quantity> specHard) {
        if (statusHard != null && !statusHard.isEmpty()) {
            return statusHard;
        }
        return specHard == null ? Map.of() : specHard;
    }

    private static boolean saturated(Quantity used, Quantity hard) {
        if (hard == null) {
            return false;
        }
        var limit = amount(hard);
        return limit.signum() > 0 && amount(used).compareTo(limit) >= 0;
    }

    private static BigDecimal cores(Quantity quantity) {
        return amount(quantity);
    }

    private static BigDecimal gibibytes(Quantity quantity) {
        var bytes = amount(quantity);
        if (bytes.signum() == 0) {
            return BigDecimal.ZERO;
        }
        if (bytes.compareTo(BigDecimal.valueOf(1024)) < 0) {
            return bytes;
        }
        return bytes.divide(GI, 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal amount(Quantity quantity) {
        if (quantity == null) {
            return BigDecimal.ZERO;
        }
        var numerical = quantity.getNumericalAmount();
        return numerical == null ? BigDecimal.ZERO : numerical;
    }

    private static String number(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
