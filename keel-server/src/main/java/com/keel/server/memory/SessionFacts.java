package com.keel.server.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.integration.audit.AuditStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Versioned derived facts. Originals and authorization remain owned by SessionHistory. */
@Service
public class SessionFacts {
    private final JdbcTemplate jdbc;
    private final SessionHistory history;
    private final ObjectMapper json;
    private final AuditStore audit;
    public SessionFacts(JdbcTemplate jdbc,SessionHistory history,ObjectMapper json,AuditStore audit) {
        this.jdbc=jdbc;this.history=history;this.json=json;this.audit=audit;
    }
    private KeelException invalid() { return new KeelException(ErrorCode.SERVER_INVALID_PARAM,"会话事实格式或来源无效"); }
    private Map<String,Object> state(String agent,String env,String session,SessionHistory.Scope scope) {
        var rows=jdbc.query("SELECT state::text,version,cursor_id FROM session_fact_state WHERE agent_name=? AND env=? AND owner_user=? AND session_id=? AND expires_at>now()",
            (rs,n)-> { try {
                var node=json.readTree(rs.getString(1));
                var result=new LinkedHashMap<String,Object>();
                result.put("version",rs.getLong(2));result.put("cursorId",rs.getString(3));
                result.put("facts",node.path("facts"));result.put("gaps",node.path("gaps"));return result;
            } catch(Exception e) {throw new IllegalStateException("Invalid stored facts",e);} },agent,env,scope.owner(),session);
        return rows.isEmpty()?Map.of("version",0,"cursorId","0","facts",List.of(),"gaps",List.of()):rows.getFirst();
    }
    public Map<String,Object> get(String agent,String env,String session) {
        var scope=history.scope(agent,env,session);
        jdbc.update("DELETE FROM session_fact_state WHERE expires_at<=now()");
        var state=state(agent,env,session,scope);
        var rows=jdbc.query("SELECT id,content FROM session_message WHERE agent_name=? AND owner_user=? AND session_id=? AND role='user' AND id>? AND expires_at>now() ORDER BY id LIMIT 9",
            (rs,n)->Map.<String,Object>of("id",rs.getString(1),"role","user","content",rs.getString(2)),agent,scope.owner(),scope.id(),Long.parseLong((String)state.get("cursorId")));
        var messages=new ArrayList<Map<String,Object>>();var skipped=new ArrayList<String>();var cursor=(String)state.get("cursorId");int chars=0;
        for(var row:rows.subList(0,Math.min(8,rows.size()))) {
            var text=(String)row.get("content");
            if(text.codePointCount(0,text.length())>6000) skipped.add((String)row.get("id"));
            else {if(chars+text.length()>6000) break;chars+=text.length();messages.add(row);}
            cursor=(String)row.get("id");
        }
        boolean more=!rows.isEmpty() && !rows.getLast().get("id").equals(cursor);
        return Map.of("state",state,"messages",messages,"skippedIds",skipped,"nextCursor",cursor,"more",more);
    }
    public Map<String,Object> save(String agent,JsonNode body) {
        var env=body.path("env").asText();var session=body.path("sessionId").asText();
        var scope=history.scope(agent,env,session);var candidate=body.path("state");
        var batch=get(agent,env,session);var canonical=json.valueToTree(batch.get("state"));
        long expected=candidate.path("version").asLong(-1);
        if(expected<0 || !body.path("evalRunId").asText("").isBlank()) throw invalid();
        if(canonical.path("version").asLong()!=expected) return Map.of("state",batch.get("state"),"saved",false);
        long cursor;
        try {cursor=Long.parseLong(candidate.path("cursorId").asText());} catch(Exception e) {throw invalid();}
        if(cursor<=canonical.path("cursorId").asLong() || !Long.toString(cursor).equals(batch.get("nextCursor"))
            || !candidate.path("facts").isArray() || candidate.path("facts").size()>128
            || !candidate.path("gaps").isArray() || candidate.path("gaps").size()>128 || candidate.toString().length()>65536) throw invalid();
        var known=new HashMap<String,JsonNode>();var sourceIds=new HashSet<String>();
        for(var f:candidate.path("facts")) {
            var id=f.path("id").asText();
            if(!id.matches("[a-f0-9]{24}") || known.put(id,f)!=null || !Set.of("active","pending","superseded","retracted").contains(f.path("status").asText())) throw invalid();
            for(var key:List.of("entity","attribute","scope","value"))
                if(!f.path(key).isTextual() || f.path(key).asText().length()>(key.equals("value")?300:80) || (!key.equals("scope") && f.path(key).asText().isBlank())) throw invalid();
            if(!f.path("sourceIds").isArray() || f.path("sourceIds").isEmpty() || f.path("sourceIds").size()>32 || !f.path("replaces").isArray() || f.path("replaces").size()>32) throw invalid();
            for(var idNode:f.path("sourceIds")) sourceIds.add(idNode.asText());
        }
        for(var id:candidate.path("gaps")) sourceIds.add(id.asText());
        for(var id:sourceIds) {
            if(!id.matches("[1-9][0-9]{0,17}") || Long.parseLong(id)>cursor) throw invalid();
            var count=jdbc.queryForObject("SELECT count(*) FROM session_message WHERE id=? AND agent_name=? AND owner_user=? AND session_id=? AND role='user' AND expires_at>now()",Integer.class,Long.parseLong(id),agent,scope.owner(),scope.id());
            if(count==null || count!=1) throw invalid();
        }
        for(var f:known.values()) {
            var targets=new ArrayList<String>();for(var r:f.path("replaces")) targets.add(r.asText());
            if(f.has("resolvedBy")) targets.add(f.path("resolvedBy").asText());
            for(var target:targets) {
                var other=known.get(target);if(other==null || other==f) throw invalid();
                for(var key:List.of("entity","attribute","scope")) if(!other.path(key).equals(f.path(key))) throw invalid();
            }
        }
        var expiry=jdbc.queryForObject("SELECT min(expires_at) FROM session_message WHERE agent_name=? AND owner_user=? AND session_id=? AND role='user' AND expires_at>now()",java.sql.Timestamp.class,agent,scope.owner(),scope.id());
        if(expiry==null) throw invalid();
        int changed;
        if(expected==0) changed=jdbc.update("""
            INSERT INTO session_fact_state VALUES (?,?,?,?,1,?,?::jsonb,?)
            ON CONFLICT(agent_name,env,owner_user,session_id) DO UPDATE SET version=session_fact_state.version+1,cursor_id=EXCLUDED.cursor_id,state=EXCLUDED.state,expires_at=EXCLUDED.expires_at
            WHERE session_fact_state.expires_at<=now()
            """,agent,env,scope.owner(),session,cursor,candidate.toString(),expiry);
        else changed=jdbc.update("UPDATE session_fact_state SET version=version+1,cursor_id=?,state=?::jsonb,expires_at=LEAST(expires_at,?) WHERE agent_name=? AND env=? AND owner_user=? AND session_id=? AND version=? AND cursor_id<? AND expires_at>now()",
            cursor,candidate.toString(),expiry,agent,env,scope.owner(),session,expected,cursor);
        if(changed==1) audit.append(agent,env,"memory.write","low","allowed","session-facts",null,ConsolePrincipal.current().username(),Map.of("count",1,"category","SESSION_FACTS"));
        return Map.of("saved",changed==1,"state",state(agent,env,session,scope));
    }
}
