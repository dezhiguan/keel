package com.keel.server.integration.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Merge independently stored console and SDK events without rewriting either audit chain. */
@Component
public class RuntimeAuditQuery {
    private final AuditStore console;
    private final String endpoint;
    private static final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public RuntimeAuditQuery(AuditStore console, @Value("${KEEL_RUNTIME_AUDIT_URL:}") String endpoint) {
        this.console = console;
        this.endpoint = endpoint == null ? "" : endpoint.replaceAll("/$", "");
    }
    public boolean enabled() { return !endpoint.isBlank(); }

    @SuppressWarnings("unchecked")
    public Map<String,Object> page(String agent,String risk,String env,String action,String kind,int page,int size) {
        if (page < 1 || page > 100 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid audit pagination");
        int take = page * size;
        var local = console.page(agent,risk,env,action,kind,1,take);
        var rows = new ArrayList<Map<String,Object>>((List<Map<String,Object>>) local.get("items"));
        int total = ((Number) local.get("total")).intValue();
        // kind belongs to control-plane configuration evidence; SDK events do not carry it.
        if (kind == null || kind.isBlank()) {
            var data = request("GET", "/events?agent="+value(agent)+"&risk="+value(risk == null ? null : risk.toLowerCase(Locale.ROOT))
                    +"&env="+value(env)+"&action="+value(action)+"&page=1&size="+take);
            total += data.path("total").asInt();
            for (var event : data.path("items")) rows.add(normalize(event));
        }
        rows.sort(Comparator.comparing((Map<String,Object> row) -> java.time.Instant.parse(String.valueOf(row.get("ts")))).reversed()
                .thenComparing(row -> String.valueOf(row.get("eventId"))));
        int start = Math.min((page-1)*size, rows.size());
        return Map.of("page",page,"size",size,"total",total,"items",rows.subList(start,Math.min(start+size,rows.size())));
    }

    public Map<String,Object> verify(String agent) {
        var local = console.verify(agent);
        var remote = request("POST","/verify?agent="+value(agent));
        var result = new LinkedHashMap<String,Object>(local);
        result.put("intact", Boolean.TRUE.equals(local.get("intact")) && remote.path("intact").asBoolean(false));
        result.put("checked", ((Number)local.getOrDefault("checked",0)).intValue()+remote.path("checked").asInt());
        if (!remote.path("intact").asBoolean(false)) result.put("brokenAt", remote.path("brokenAt").asText());
        return result;
    }

    private JsonNode request(String method,String path) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(endpoint+"/api/v1/audit"+path)).timeout(Duration.ofSeconds(10));
            var response = http.send(builder.method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) throw new IllegalStateException("Runtime audit query rejected");
            var envelope=json.readTree(response.body());
            if(!"OK".equals(envelope.path("code").asText()) || !envelope.path("data").isObject()) throw new IllegalStateException("Invalid runtime audit response");
            return envelope.path("data");
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("Runtime audit query interrupted",error);
        } catch (java.io.IOException error) { throw new IllegalStateException("Runtime audit query unavailable",error); }
    }
    static Map<String,Object> normalize(JsonNode event) {
        var row = new LinkedHashMap<String,Object>();
        for (var field : List.of("ts","agent","env","action","resource","decision","payload","hash","prevHash","hashVerified"))
            if(event.has(field)) row.put(field,json.convertValue(event.get(field),Object.class));
        row.put("eventId",event.path("event_id").asText());
        row.put("traceId",event.path("trace_id").asText(""));
        row.put("risk",event.path("risk").asText().toUpperCase(Locale.ROOT));
        row.put("actor",Map.of("userId","service:"+event.path("agent").asText()));
        if(event.has("approver")) row.put("approver",event.path("approver").asText());
        return row;
    }
    private static String value(String text) { return URLEncoder.encode(text == null ? "" : text,StandardCharsets.UTF_8); }
}
