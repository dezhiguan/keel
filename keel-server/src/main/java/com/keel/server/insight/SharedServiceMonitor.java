package com.keel.server.insight;

import com.keel.server.integration.prometheus.PrometheusClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SharedServiceMonitor {
    private final PrometheusClient prometheus;

    public SharedServiceMonitor(PrometheusClient prometheus) {
        this.prometheus = prometheus;
    }

    public Map<String, Object> services() {
        Double up = prometheus.query("up{job=\"rag-forge\"}");
        var service = new LinkedHashMap<String, Object>();
        service.put("name", "rag-forge");
        service.put("role", "知识检索");
        service.put("p95", up == null ? null : up);
        service.put("status", up == null ? null : (up > 0 ? "ONLINE" : "OFFLINE"));
        var kpi = new LinkedHashMap<String, Object>();
        kpi.put("p95Seconds", up);
        var ragforge = new LinkedHashMap<String, Object>();
        ragforge.put("kpi", kpi);
        var body = new LinkedHashMap<String, Object>();
        body.put("services", List.of(service));
        body.put("ragforge", ragforge);
        return body;
    }
}
