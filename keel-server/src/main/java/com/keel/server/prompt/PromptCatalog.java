package com.keel.server.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Manifest prompts.items for code agents. Dify agents are not listed. */
@Component
public class PromptCatalog {
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{0,38}[a-z0-9]$");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    @org.springframework.beans.factory.annotation.Value("${keel.ci.client-id:}")
    private String ciClient = "";

    private Actor actor() {
        var principal = com.keel.server.auth.ConsolePrincipal.current();
        if (principal != null && "SERVICE".equals(principal.mode()) && !ciClient.isBlank()
                && ciClient.equals(principal.username()))
            return new Actor(principal.username(), "", "ADMIN");
        return Actor.current();
    }

    public PromptCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Item(String agent, String name, String type, String ownerOrg, String ownerUser) {
        public String fullName() {
            return agent + "/" + name;
        }
    }

    public record Actor(String userId, String org, String role) {
        public static Actor current() {
            var principal = com.keel.server.auth.ConsolePrincipal.current();
            if (principal == null) {
                throw new KeelException(ErrorCode.AUTH_UNAUTHENTICATED, ErrorCode.AUTH_UNAUTHENTICATED.message());
            }
            return new Actor(principal.username(), principal.org(), principal.platformRole());
        }

        public boolean admin() {
            return "ADMIN".equals(role);
        }
    }

    public List<Item> declared(String agentFilter) {
        return declared(agentFilter, actor());
    }

    /** Background drift scans have no console request and must inspect every organization. */
    public List<Item> declaredForDriftScan() {
        return declared(null, new Actor("keel", "", "ADMIN"));
    }

    private List<Item> declared(String agentFilter, Actor actor) {
        var rows = jdbc.query("""
                SELECT a.name, a.runtime, a.owner_org, a.owner_user, v.manifest_json::text AS manifest_json
                FROM agent a
                LEFT JOIN LATERAL (
                    SELECT manifest_json FROM agent_version
                    WHERE agent_name = a.name
                    ORDER BY released_at DESC
                    LIMIT 1
                ) v ON true
                WHERE a.kind = 'AGENT' AND a.runtime <> 'dify' AND a.status <> 'RETIRED'
                  AND (? = '' OR a.name = ?)
                ORDER BY a.name
                """, (rs, n) -> new String[] {
                rs.getString("name"), rs.getString("owner_org"), rs.getString("owner_user"), rs.getString("manifest_json")
        }, agentFilter == null ? "" : agentFilter, agentFilter == null ? "" : agentFilter);
        var items = new ArrayList<Item>();
        for (var row : rows) {
            if (!actor.admin() && !actor.org().equals(row[1])) {
                continue;
            }
            items.addAll(readItems(row[0], row[1], row[2], row[3]));
        }
        return items;
    }

    public Item require(String agent, String name) {
        return declared(agent).stream()
                .filter(item -> item.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new KeelException(ErrorCode.PROMPT_NOT_DECLARED, ErrorCode.PROMPT_NOT_DECLARED.message()));
    }

    public boolean knownAgent(String agent) {
        var count = jdbc.queryForObject("""
                SELECT count(*) FROM agent
                WHERE name = ? AND kind = 'AGENT' AND runtime <> 'dify' AND status <> 'RETIRED'
                """, Integer.class, agent);
        return count != null && count > 0 && visible(ownerOrg(agent));
    }

    public void authorize(String agent) {
        var actor = actor();
        if (actor.admin()) {
            if (ownerOrg(agent) == null) {
                throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
            }
            return;
        }
        var org = ownerOrg(agent);
        if (org == null || !org.equals(actor.org())) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "只能修改本组织智能体的提示词");
        }
    }

    public String ownerOrg(String agent) {
        var rows = jdbc.query("SELECT owner_org FROM agent WHERE name = ?", (rs, n) -> rs.getString(1), agent);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean visible(String ownerOrg) {
        var actor = actor();
        return actor.admin() || actor.org().equals(ownerOrg);
    }

    private List<Item> readItems(String agent, String ownerOrg, String ownerUser, String manifest) {
        var items = new ArrayList<Item>();
        if (manifest == null || manifest.isBlank()) {
            return items;
        }
        try {
            JsonNode root = json.readTree(manifest);
            var declared = root.path("spec").path("prompts").path("items");
            if (!declared.isArray()) {
                return items;
            }
            declared.forEach(item -> {
                var name = item.path("name").asText("");
                var type = item.path("type").asText("");
                if (NAME.matcher(name).matches() && ("text".equals(type) || "chat".equals(type))) {
                    items.add(new Item(agent, name, type, ownerOrg, ownerUser));
                }
            });
        } catch (Exception e) {
            return items;
        }
        return items;
    }
}
