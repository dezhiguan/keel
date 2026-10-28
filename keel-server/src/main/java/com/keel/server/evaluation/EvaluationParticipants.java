package com.keel.server.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.common.model.EvaluationParticipant;
import com.keel.server.auth.*;
import com.keel.server.common.KeelException;
import com.keel.server.registry.service.DebugSessionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Passwords never enter this service: each consent comes from its own verified USER request. */
@Service
public class EvaluationParticipants {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DebugSessionService sessions;
    private final ConsoleUsers users;
    private final ConsoleAuthProperties auth;
    public EvaluationParticipants(JdbcTemplate jdbc,ObjectMapper json,DebugSessionService sessions,ConsoleUsers users,ConsoleAuthProperties auth) {
        this.jdbc=jdbc;this.json=json;this.sessions=sessions;this.users=users;this.auth=auth;
    }
    public void initialize(String run,String agent,String env,ConsolePrincipal primary) {
        if(!"keel-assistant".equals(agent) || !"staging".equals(env))return;
        if(auth.openForTests() || "local".equals(auth.mode()))return;
        verify(primary);
        add(run,0,primary);
    }
    @Transactional
    public EvaluationParticipant consent(String run) {
        var state=run(run);var actor=ConsolePrincipal.current();verify(actor);scope(state,actor);
        var primary=participant(run,0);
        if(primary==null || primary.user.userId().equals(actor.userId()) || primary.user.username().equalsIgnoreCase(actor.username()))throw denied();
        // Serialize both independently authenticated contenders; never replace an existing identity.
        jdbc.queryForList("SELECT run_id FROM eval_run WHERE run_id=? FOR UPDATE",run);
        state=run(run);
        var other=participant(run,1);
        if(other!=null && !other.user.userId().equals(actor.userId()))throw denied();
        if(other==null)add(run,1,actor);
        return evidence(state,Objects.requireNonNull(participant(run,1)),null);
    }
    public Context context(String run,int turn) {
        if(turn<0 || turn>1)throw denied();
        var state=run(run);var actor=participant(run,turn);
        if(actor==null)return null;
        verify(actor.user);scope(state,actor.user);
        if(!actor.expires.isAfter(Instant.now()))throw denied();
        sessions.require(state.agent,state.env,actor.user.userId(),actor.session);
        return new Context(actor.user,actor.session,sessions.history(state.agent,actor.user.userId(),actor.session),evidence(state,actor,null));
    }
    /** Called inside the bridge transaction. A duplicate HTTP callback never duplicates PG turns. */
    public EvaluationParticipant store(String run,String caseId,int turn,String question,String answer,String trace) {
        if(turn<0 || turn>1)throw denied();
        var state=run(run);var actor=participant(run,turn);
        if(actor==null || answer==null || trace==null || !trace.matches("[0-9a-f]{32}"))throw denied();
        verify(actor.user);scope(state,actor.user);
        if(!actor.expires.isAfter(Instant.now()))throw denied();
        jdbc.queryForList("SELECT run_id FROM eval_run_participant WHERE run_id=? AND participant_index=? FOR UPDATE",run,turn);
        var existing=jdbc.queryForList("SELECT question,answer FROM eval_turn WHERE run_id=? AND case_id=? AND turn_index=?",run,caseId,turn);
        if(existing.isEmpty())sessions.appendTurn(state.agent,actor.user.userId(),actor.session,question,answer,trace);
        else if(!Objects.equals(question,existing.getFirst().get("question")) || !Objects.equals(answer,existing.getFirst().get("answer")))throw denied();
        var count=jdbc.queryForObject("SELECT count(*) FROM session_message WHERE session_id=? AND owner_user=? AND agent_name=? AND expires_at>now()",Integer.class,actor.session,actor.user.userId(),state.agent);
        if(count==null || count<2)throw denied();
        var storage=Map.<String,Object>of("source","Keel owner-bound PG evaluation session","ownerFingerprint",EvaluationWorkerBridge.hash(actor.user.userId()),
                "authorizationId",actor.authorization,"sessionId",UserReadContext.sessionHash(actor.user.userId(),actor.session),"messageCount",count,"turnIndex",turn);
        return evidence(state,actor,storage);
    }
    public void requireComplete(String run,String caseId) {
        var first=participant(run,0);var second=participant(run,1);
        if(first==null || second==null || first.user.userId().equals(second.user.userId()) || first.session.equals(second.session)
                || first.authorization.equals(second.authorization))throw denied();
        for(int turn=0;turn<2;turn++) {
            var context=context(run,turn);if(context==null)throw denied();
            var saved=jdbc.queryForList("SELECT evidence::text FROM eval_turn WHERE run_id=? AND case_id=? AND turn_index=?",run,caseId,turn);
            if(saved.size()!=1)throw denied();
            try {
                var proof=json.readTree((String)saved.getFirst().get("evidence")).path("sessionEvidence").path("participant");
                var actual=context.evidence;
                if(!proof.path("authorizationId").asText().equals(actual.authorizationId())
                        || !proof.path("ownerFingerprint").asText().equals(actual.ownerFingerprint())
                        || !proof.path("storage").path("sessionId").asText().equals(actual.sessionId())
                        || proof.path("storage").path("messageCount").asInt()!=2)throw denied();
            } catch(Exception error){throw denied();}
        }
    }
    private void add(String run,int index,ConsolePrincipal user) {
        var state=run(run);scope(state,user);var session=sessions.create(state.agent,state.env,user.userId());
        jdbc.update("INSERT INTO eval_run_participant(run_id,participant_index,user_id,principal,session_id,authorization_id,expires_at) VALUES (?,?,?,?::jsonb,?,?,?)",
                run,index,user.userId(),encode(user),session.get("id"),UUID.randomUUID().toString(),java.sql.Timestamp.from(state.expires));
    }
    private EvaluationParticipant evidence(State state,Participant actor,Map<String,Object> storage) {
        var out=new LinkedHashMap<String,Object>();out.put("schema_version","keel/v1");
        out.put("source","Keel independently authenticated evaluation participant");out.put("authenticationSource","auth-gateway");
        out.put("ownerFingerprint",EvaluationWorkerBridge.hash(actor.user.userId()));out.put("authorizationId",actor.authorization);
        out.put("participantIndex",actor.index);out.put("sessionId",UserReadContext.sessionHash(actor.user.userId(),actor.session));out.put("sessionPersisted",true);
        out.put("agent",state.agent);out.put("env",state.env);out.put("runId",state.run);out.put("authorizationExpiresAt",actor.expires.toString());
        if(storage!=null)out.put("storage",storage);
        return json.convertValue(out,EvaluationParticipant.class);
    }
    private void scope(State state,ConsolePrincipal actor) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_employee_read_scope WHERE agent_name=? AND environment=? AND target_org=? AND enabled=true)",Boolean.class,state.agent,state.env,actor.org())))throw denied();
    }
    private void verify(ConsolePrincipal actor) {
        if(auth.openForTests() || "local".equals(auth.mode()) || actor==null || !"USER".equals(actor.mode()) || actor.readOnly()
                || actor.userId()==null || actor.userId().isBlank())throw denied();
        var current=users.find(actor.username()).orElseThrow(EvaluationParticipants::denied);
        if(!Set.of("ADMIN","VIEWER").contains(current.platformRole()) || !current.platformRole().equals(actor.platformRole()) || !current.org().equals(actor.org()))throw denied();
    }
    private State run(String id) {
        var rows=jdbc.query("SELECT agent_name,env,state,created_at FROM eval_run WHERE run_id=? AND runner='acceptance-v1'",(rs,n)->
                new State(id,rs.getString(1),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant().plusSeconds(3600)),id);
        if(rows.size()!=1)throw denied();var state=rows.getFirst();
        if(!"keel-assistant".equals(state.agent) || !"staging".equals(state.env) || !Set.of("QUEUED","RUNNING").contains(state.status) || !state.expires.isAfter(Instant.now()))throw denied();
        return state;
    }
    private Participant participant(String run,int index) {
        var rows=jdbc.query("SELECT principal::text,session_id,authorization_id,expires_at FROM eval_run_participant WHERE run_id=? AND participant_index=?",(rs,n)-> {
            try{return new Participant(index,json.readValue(rs.getString(1),ConsolePrincipal.class),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant());}
            catch(Exception error){throw denied();}
        },run,index);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw denied();}}
    private static KeelException denied(){return new KeelException(ErrorCode.TOOL_NOT_GRANTED,"真实用户独立评测授权缺失、过期或不匹配");}
    public record Context(ConsolePrincipal user,String pgSession,List<Map<String,String>> history,EvaluationParticipant evidence) {}
    private record State(String run,String agent,String env,String status,Instant expires) {}
    private record Participant(int index,ConsolePrincipal user,String session,String authorization,Instant expires) {}
}
