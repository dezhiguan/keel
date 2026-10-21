package com.keel.server.tool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.auth.UserReadContext;
import com.keel.server.common.KeelException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** Business read projection: no runtime probe, secrets or raw manifest leave this service. */
@Service
public class EmployeeReadService {
    public static final Set<String> READ_ENVIRONMENTS=Set.of("staging");
    public static final Set<String> RETIRED_ENVIRONMENTS=Set.of("dev","test");
    public static final Set<String> BUSINESS_ENVIRONMENTS=Set.of("staging","prod");
    private final JdbcTemplate jdbc;
    private final UserReadContext contexts;
    public EmployeeReadService(JdbcTemplate jdbc,UserReadContext contexts){this.jdbc=jdbc;this.contexts=contexts;}
    public Map<String,Object> authorizeKnowledge(String agent,String runtimeEnv,JsonNode args){
        if(args.size()!=2 || !args.has("_authorization") || !args.has("_session"))throw invalid("知识授权参数无效");
        var actor=contexts.verify(args.path("_authorization").asText(""),agent,runtimeEnv);
        if(actor.session()==null || !actor.session().equals(args.path("_session").asText("")))
            throw new KeelException(ErrorCode.TOOL_NOT_GRANTED,"用户会话授权不匹配");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_employee_read_scope WHERE agent_name=? AND environment=? AND target_org=? AND enabled)",Boolean.class,agent,runtimeEnv,actor.org())))
            throw new KeelException(ErrorCode.TOOL_NOT_GRANTED,"当前用户及环境未获只读授权");
        return new LinkedHashMap<>(Map.of("authorized",true,"_actor",actor.username()));
    }
    public Map<String,Object> read(String tool,String agent,String runtimeEnv,JsonNode args){
        var actor=contexts.verify(args.path("_authorization").asText(""),agent,runtimeEnv);
        if(actor.session()==null || !actor.session().equals(args.path("_session").asText("")))
            throw new KeelException(ErrorCode.TOOL_NOT_GRANTED,"用户会话授权不匹配");
        var env=args.path("env").asText("");
        if(!READ_ENVIRONMENTS.contains(env))throw invalid("只读查询当前仅开放 staging；dev/test 已停用，prod 未授权");
        boolean detail="keel.employees.detail".equals(tool);
        var allowed=detail ? Set.of("env","id","_authorization","_session") : Set.of("env","page","size","_authorization","_session");
        args.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw invalid("工具参数不在白名单");});
        var approved=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_employee_read_scope WHERE agent_name=? AND environment=? AND target_org=? AND enabled)",Boolean.class,agent,env,actor.org());
        if(!Boolean.TRUE.equals(approved))throw new KeelException(ErrorCode.TOOL_NOT_GRANTED,"目标环境或组织未授权");
        String id=detail?args.path("id").asText(""):"";
        if(detail && !id.matches("[a-z][a-z0-9-]{1,62}"))throw invalid("需要有效员工ID");
        int page=integer(args,"page",1,1,1000),size=integer(args,"size",10,1,20);
        var params=new ArrayList<Object>(List.of(env,actor.org()));
        var where=" WHERE a.kind='AGENT' AND v.env=? AND a.owner_org=?";
        if(detail){where+=" AND a.name=?";params.add(id);}
        var base=" FROM agent a JOIN LATERAL (SELECT env,version,manifest_json,released_at FROM agent_version WHERE agent_name=a.name AND env=? ORDER BY released_at DESC,id DESC LIMIT 1) v ON true";
        // env appears once inside the lateral subquery and once in the resource filter.
        params.addFirst(env);
        Long total=jdbc.queryForObject("SELECT count(*)"+base+where,Long.class,params.toArray());
        var paged=new ArrayList<>(params);paged.add(detail?1:size);paged.add(detail?0:(page-1)*size);
        var items=jdbc.query("SELECT a.name,a.display_name,a.status,v.env,v.version,v.released_at,v.manifest_json->'metadata'->>'description' AS responsibility"+base+where+" ORDER BY a.name LIMIT ? OFFSET ?",(rs,n)->{
            var item=new LinkedHashMap<String,Object>();item.put("id",rs.getString("name"));item.put("name",rs.getString("display_name"));
            item.put("responsibility",rs.getString("responsibility"));item.put("env",rs.getString("env"));item.put("version",rs.getString("version"));
            item.put("registrationStatus",rs.getString("status"));item.put("health","unknown");
            item.put("healthNote","未执行健康探针，ONLINE仅为登记状态");item.put("registeredAt",rs.getTimestamp("released_at").toInstant().toString());return item;
        },paged.toArray());
        if(detail && items.isEmpty())throw new KeelException(ErrorCode.SERVER_NOT_FOUND,"授权范围内不存在该环境的员工");
        var out=new LinkedHashMap<String,Object>();out.put("items",items);out.put("total",total);out.put("page",page);out.put("size",detail?1:size);
        out.put("complete",detail || (page==1 && total!=null && items.size()==total));out.put("hasMore",!detail && total!=null && (long)page*size<total);
        out.put("queriedAt",Instant.now().toString());out.put("source","Keel registered employee read API");out.put("targetEnv",env);out.put("scopeOrg",actor.org());out.put("_actor",actor.username());return out;
    }
    static int integer(JsonNode args,String key,int fallback,int min,int max){
        var n=args.get(key);if(n==null)return fallback;
        if(!n.isIntegralNumber() || !n.canConvertToInt() || n.asInt()<min || n.asInt()>max)throw invalid("分页参数无效");return n.asInt();
    }
    private static KeelException invalid(String message){return new KeelException(ErrorCode.SERVER_INVALID_PARAM,message);}
}
