package com.keel.server.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.auth.UserReadContext;
import com.keel.server.common.KeelException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** User-delegated retrieval. Service identity alone never grants access to user history. */
@Service
public class CrossSessionHistory {
    private final JdbcTemplate jdbc;
    private final UserReadContext contexts;
    public CrossSessionHistory(JdbcTemplate jdbc, UserReadContext contexts) {
        this.jdbc=jdbc; this.contexts=contexts;
    }
    public Map<String,Object> search(String agent,String env,JsonNode args) {
        var allowed=Set.of("query","exact","role","limit","_authorization","_session");
        args.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw invalid();});
        var actor=contexts.verify(args.path("_authorization").asText(""),agent,env);
        var session=args.path("_session").asText("");
        if(actor.session()==null || !actor.session().equals(session))throw denied();
        var current=jdbc.query("""
            SELECT s.id FROM agent_session s JOIN agent_history_policy p
              ON p.agent_name=s.agent_name AND p.env=s.env
            WHERE s.agent_name=? AND s.env=? AND s.owner_user=?
              AND encode(sha256(convert_to(s.owner_user||':'||s.id,'UTF8')),'hex')=?
            """,(rs,n)->rs.getString(1),agent,env,actor.userId(),session);
        if(current.size()!=1)throw denied();
        var query=args.path("query").asText("").trim();
        var exact=args.path("exact").asBoolean(false);
        var role=args.path("role").asText("user");
        var limit=args.path("limit").asInt(4);
        if(!args.path("query").isTextual() || (args.has("role") && !args.get("role").isTextual()) || query.isEmpty() || query.length()>500 || !Set.of("user","all").contains(role)
           || limit<1 || limit>6 || (args.has("exact") && !args.get("exact").isBoolean())
           || (args.has("limit") && !args.get("limit").isIntegralNumber()))throw invalid();
        var terms=exact ? List.of(query.toLowerCase(Locale.ROOT)) : SessionHistory.terms(query);
        var items=new ArrayList<Map<String,Object>>();
        if(!terms.isEmpty()) {
            var score=String.join("+",Collections.nCopies(terms.size(),"CASE WHEN strpos(lower(m.content),?)>0 THEN 1 ELSE 0 END"));
            var params=new ArrayList<Object>(terms);
            params.addAll(List.of(agent,env,actor.userId(),current.getFirst()));
            var roleFilter="user".equals(role)?" AND m.role='user'":"";
            params.add(limit);
            var rows=jdbc.query("SELECT * FROM (SELECT m.id,m.role,m.content,m.created_at,m.session_id,s.title,"+score+" AS score "
                +"FROM session_message m JOIN agent_session s ON s.id=m.session_id AND s.agent_name=m.agent_name AND s.owner_user=m.owner_user "
                +"WHERE s.agent_name=? AND s.env=? AND s.owner_user=? AND s.id<>? AND m.expires_at>now()"+roleFilter
                +") hits WHERE score>0 ORDER BY score DESC,id DESC LIMIT ?",(rs,n)->{
                    var row=new LinkedHashMap<String,Object>();row.put("id",rs.getString("id"));
                    row.put("role",rs.getString("role"));row.put("content",rs.getString("content"));
                    row.put("sessionId",rs.getString("session_id"));row.put("title",rs.getString("title"));
                    row.put("createdAt",rs.getTimestamp("created_at").toInstant().toString());return row;
                },params.toArray());
            for(var row:rows) {
                var text=(String)row.get("content");var lower=text.toLowerCase(Locale.ROOT);int at=Integer.MAX_VALUE;
                for(var term:terms){int pos=lower.indexOf(term);if(pos>=0)at=Math.min(at,pos);}
                int offset=Math.max(0,text.codePointCount(0,Math.min(at,text.length()))-160);
                var item=new LinkedHashMap<>(SessionHistory.excerpt(row,offset,1200));
                for(var key:List.of("sessionId","title","createdAt"))item.put(key,row.get(key));
                item.put("url","/agents/"+agent+"/debug?session="+row.get("sessionId"));items.add(item);
            }
        }
        var result=new LinkedHashMap<String,Object>();result.put("items",items);
        result.put("scope","previous_sessions");result.put("matchMode",exact?"exact":"keywords");
        result.put("exhaustive",false);result.put("queriedAt",Instant.now().toString());
        result.put("_actor",actor.username());return result;
    }
    private static KeelException invalid(){return new KeelException(ErrorCode.SERVER_INVALID_PARAM,"历史检索参数无效");}
    private static KeelException denied(){return new KeelException(ErrorCode.TOOL_NOT_GRANTED,"历史检索缺少有效的当前用户会话授权");}
}
