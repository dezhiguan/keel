package com.keel.server.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.integration.audit.AuditStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CAS session compression, separate from immutable original messages and user memory. */
@Service
public class SessionSummaries {
    private final JdbcTemplate jdbc;
    private final AuditStore audit;
    public SessionSummaries(JdbcTemplate jdbc,AuditStore audit) { this.jdbc = jdbc; this.audit=audit; }

    private void authorize(String agent, String env, String owner, String session) {
        if (!List.of("dev", "test", "staging", "prod").contains(env) || owner.isBlank() || owner.length()>128
                || session.isBlank() || session.length()>128)
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "会话摘要范围无效");
        var orgs=jdbc.query("SELECT owner_org FROM agent WHERE name=?",(rs,n)->rs.getString(1),agent);
        if(orgs.isEmpty()) throw new KeelException(ErrorCode.SERVER_NOT_FOUND,"智能体不存在");
        MemoryService.authorize(agent,orgs.getFirst(),owner,true);
    }

    public Map<String,Object> get(String agent,String env,String owner,String session) {
        authorize(agent,env,owner,session);
        var rows=jdbc.query("""
                SELECT version,through_id,content FROM session_summary
                WHERE agent_name=? AND env=? AND owner_user=? AND session_id=? AND expires_at>now()
                """,(rs,n)->Map.<String,Object>of("version",rs.getLong(1),"throughId",Long.toString(rs.getLong(2)),"content",rs.getString(3)),agent,env,owner,session);
        return rows.isEmpty()?Map.of("version",0,"throughId","0","content",""):rows.getFirst();
    }

    public Map<String,Object> save(String agent,JsonNode body) {
        var env=body.path("env").asText();var owner=body.path("ownerUser").asText();var session=body.path("sessionId").asText();
        authorize(agent,env,owner,session);
        long version=body.path("version").asLong(-1),through;
        try { through=Long.parseLong(body.path("throughId").asText()); }
        catch(NumberFormatException error) { throw new KeelException(ErrorCode.SERVER_INVALID_PARAM,"摘要游标无效"); }
        var content=body.path("content").asText("");
        if(version<0 || through<=0 || content.isBlank() || content.length()>8192 || !body.path("evalRunId").asText("").isBlank())
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM,"会话摘要无效或评测流量禁止写入");
        jdbc.update("DELETE FROM session_summary WHERE expires_at<=now()");
        int changed;
        if(version==0) changed=jdbc.update("""
                INSERT INTO session_summary(agent_name,env,owner_user,session_id,version,through_id,content,expires_at)
                VALUES (?,?,?,?,1,?,?,now()+interval '7 days')
                ON CONFLICT(agent_name,env,owner_user,session_id) DO UPDATE
                SET version=session_summary.version+1,through_id=EXCLUDED.through_id,
                    content=EXCLUDED.content,expires_at=EXCLUDED.expires_at
                WHERE session_summary.expires_at<=now()
                """,agent,env,owner,session,through,content);
        else changed=jdbc.update("""
                UPDATE session_summary SET version=version+1,through_id=?,content=?
                WHERE agent_name=? AND env=? AND owner_user=? AND session_id=? AND version=? AND through_id<? AND expires_at>now()
                """,through,content,agent,env,owner,session,version,through);
        if(changed==1) audit.append(agent,env,"memory.write","low","allowed","session-summary",null,
                ConsolePrincipal.current().username(),Map.of("count",1,"category","SESSION_SUMMARY"));
        var result=new LinkedHashMap<>(get(agent,env,owner,session));result.put("saved",changed==1);return result;
    }
}
