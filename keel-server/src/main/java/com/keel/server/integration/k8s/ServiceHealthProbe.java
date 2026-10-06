package com.keel.server.integration.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Reads ready replica counts and HTTP liveness. A miss is unknown, not a fake ONLINE. */
public class ServiceHealthProbe {
    private final KubernetesClient kubernetes;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public ServiceHealthProbe(KubernetesClient kubernetes) {
        this.kubernetes = kubernetes;
    }

    public static boolean inCluster() {
        var host = System.getenv("KUBERNETES_SERVICE_HOST");
        return host != null && !host.isBlank()
                && Files.isRegularFile(Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token"));
    }

    /** Replica ratio from Endpoints. Outside the cluster this is unknown and does not dial cluster DNS. */
    public Result cluster(String namespace, String service, int port, String path, int maxStatus) {
        if (!inCluster()) {
            return Result.unknown();
        }
        var instances = instances(namespace, service);
        if (instances != null) {
            return new Result(instances, statusOf(instances));
        }
        var url = "http://" + service + "." + namespace + ".svc.cluster.local:" + port + path;
        return new Result(null, Boolean.TRUE.equals(httpUp(url, maxStatus)) ? "ONLINE" : "OFFLINE");
    }

    public Result http(String url, int maxStatus) {
        if (url == null || url.isBlank()) {
            return Result.unknown();
        }
        var up = httpUp(url, maxStatus);
        if (up == null) {
            return Result.unknown();
        }
        return new Result(null, up ? "ONLINE" : "OFFLINE");
    }

    static String statusOf(String instances) {
        var parts = instances.split("/", -1);
        int ready = Integer.parseInt(parts[0]);
        int total = Integer.parseInt(parts[1]);
        if (ready <= 0) {
            return "OFFLINE";
        }
        return ready < total ? "DEGRADED" : "ONLINE";
    }

    private String instances(String namespace, String name) {
        if (kubernetes == null) {
            return null;
        }
        try {
            var endpoints = kubernetes.endpoints().inNamespace(namespace).withName(name).get();
            if (endpoints == null || endpoints.getSubsets() == null) {
                return null;
            }
            int ready = 0;
            int notReady = 0;
            for (var subset : endpoints.getSubsets()) {
                if (subset.getAddresses() != null) {
                    ready += subset.getAddresses().size();
                }
                if (subset.getNotReadyAddresses() != null) {
                    notReady += subset.getNotReadyAddresses().size();
                }
            }
            int total = ready + notReady;
            return total == 0 ? null : ready + "/" + total;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Boolean httpUp(String url, int maxStatus) {
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < maxStatus;
        } catch (Exception e) {
            return false;
        }
    }

    public record Result(String instances, String status) {
        public static Result unknown() {
            return new Result(null, null);
        }
    }
}
