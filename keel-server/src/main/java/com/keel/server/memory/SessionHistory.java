package com.keel.server.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Reads immutable originals in one owner-scoped platform session. */
@Service
public class SessionHistory {
    private final JdbcTemplate jdbc;
    private final SessionSummaries summaries;
    public SessionHistory(JdbcTemplate jdbc, SessionSummaries summaries) {
        this.jdbc=jdbc; this.summaries=summaries;
    }
    private record Scope(String id,String owner) {}
    private Scope scope(String agent,String env,String session) {
        var p=ConsolePrincipal.current();
        if(p==null || "PREVIEW".equals(p.mode()) || ("SERVICE".equals(p.mode()) && !agent.equals(p.userId())))
            throw new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, ErrorCode.AUTH_CONSOLE_FORBIDDEN.message());
        if(!List.of("dev","test","staging","prod").contains(env) || !session.matches("[a-f0-9]{64}"))
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, ErrorCode.SERVER_INVALID_PARAM.message());
        var found=jdbc.query("""
            SELECT id,owner_user FROM agent_session WHERE agent_name=? AND env=?
            AND encode(sha256(convert_to(owner_user||':'||id,'UTF8')),'hex')=?
            """,(rs,n)->new Scope(rs.getString(1),rs.getString(2)),agent,env,session);
        if(found.isEmpty()) throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        var result=found.getFirst();
        if(!"SERVICE".equals(p.mode()) && (p.readOnly() || !result.owner().equals(p.userId())))
            throw new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, ErrorCode.AUTH_CONSOLE_FORBIDDEN.message());
        return result;
    }
    static List<String> terms(String text) {
        var terms=new LinkedHashSet<String>();
        var stop=Set.of("之前","什么","帮我","让我","记得","曾经","我们","你说","我说","那个","这个","时候","一下","会话","聊天","记录","请问","可以","原文","摘要");
        var matcher=Pattern.compile("[a-z0-9_][a-z0-9_-]{1,63}|[\\p{IsHan}]+").matcher(text.toLowerCase(Locale.ROOT));
        while(matcher.find() && terms.size()<48) {
            var word=matcher.group();
            if(word.codePoints().allMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN)) {
                var cps=word.codePoints().toArray();
                for(int i=0;i+1<cps.length && terms.size()<48;i++) {
                    var term=new String(cps,i,2); if(!stop.contains(term)) terms.add(term);
                }
            } else terms.add(word);
        }
        return List.copyOf(terms);
    }
    private static long positive(String id) {
        try {long value=Long.parseLong(id); if(value>0) return value;} catch(NumberFormatException ignored) {}
        throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, ErrorCode.SERVER_INVALID_PARAM.message());
    }
    public Map<String,Object> search(String agent,JsonNode body) {
        var env=body.path("env").asText();var session=body.path("sessionId").asText();
        var scope=scope(agent,env,session);var query=body.path("query").asText();var limit=body.path("limit").asInt(4);
        if(query.isBlank() || query.length()>2000 || limit<1 || limit>6) throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, ErrorCode.SERVER_INVALID_PARAM.message());
        var terms=terms(query);if(terms.isEmpty()) return Map.of("items",List.of());
        var score=String.join("+",Collections.nCopies(terms.size(),"CASE WHEN strpos(lower(content),?)>0 THEN 1 ELSE 0 END"));
        var params=new ArrayList<Object>(terms);params.add(scope.id());params.add(agent);params.add(scope.owner());
        var before=body.path("beforeId").asText("");
        var filter=before.isBlank()?"":" AND id<?";
        if(!before.isBlank()) params.add(positive(before));params.add(limit);
        var rows=jdbc.query("SELECT id,role,content,"+score+" AS score FROM session_message WHERE session_id=? AND agent_name=? AND owner_user=? AND expires_at>now()"+filter+" ORDER BY score DESC,id DESC LIMIT ?",
            (rs,n)->Map.<String,Object>of("id",rs.getString("id"),"role",rs.getString("role"),"content",rs.getString("content"),"score",rs.getInt("score")),params.toArray());
        var items=new ArrayList<Map<String,Object>>();
        for(var row:rows) if((int)row.get("score")>0) {
            var text=(String)row.get("content");var lower=text.toLowerCase(Locale.ROOT);var at=Integer.MAX_VALUE;
            for(var term:terms) {var pos=lower.indexOf(term);if(pos>=0) at=Math.min(at,pos);}
            var offset=Math.max(0,text.codePointCount(0,Math.min(at,text.length()))-160);
            items.add(excerpt(row,offset,1200));
        }
        return Map.of("items",items);
    }
    public Map<String,Object> read(String agent,String env,String session,String id,int offset,int limit,String owner,Long version) {
        var scope=scope(agent,env,session);positive(id);
        if(offset<0 || limit<1 || limit>4000) throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, ErrorCode.SERVER_INVALID_PARAM.message());
        if(version!=null) {
            if(owner==null || owner.isBlank() || version<1) throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, ErrorCode.SERVER_INVALID_PARAM.message());
            var state=summaries.get(agent,env,owner,session);
            if(((Number)state.get("version")).longValue()!=version)
                return Map.of("stale",true,"summary",state,"items",List.of());
            if(!SessionSummaries.sourceIds((String)state.get("content")).contains(id))
                return Map.of("stale",false,"items",List.of());
        }
        var rows=jdbc.query("SELECT id,role,content FROM session_message WHERE session_id=? AND agent_name=? AND owner_user=? AND id=? AND expires_at>now()",
            (rs,n)->Map.<String,Object>of("id",rs.getString(1),"role",rs.getString(2),"content",rs.getString(3)),scope.id(),agent,scope.owner(),positive(id));
        return Map.of("stale",false,"items",rows.stream().map(r->excerpt(r,offset,limit)).toList());
    }
    private static Map<String,Object> excerpt(Map<String,Object> row,int offset,int limit) {
        var text=(String)row.get("content");var total=text.codePointCount(0,text.length());
        var start=Math.min(offset,total);var end=Math.min(total,start+limit);String digest;
        try {digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
        return Map.of("id",row.get("id"),"role",row.get("role"),"content",text.substring(text.offsetByCodePoints(0,start),text.offsetByCodePoints(0,end)),
            "offset",start,"totalChars",total,"sha256",digest,"hasMore",end<total);
    }
}
