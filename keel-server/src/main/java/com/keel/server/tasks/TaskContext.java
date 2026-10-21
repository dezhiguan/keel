package com.keel.server.tasks;

import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.auth.ConsoleSigningKey;
import com.keel.server.auth.ConsoleUsers;
import com.keel.server.common.KeelException;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Separate purpose from employee read tickets; no user identity is accepted from a model. */
@Service
public class TaskContext {
    private final ConsoleSigningKey key;
    private final ConsoleUsers users;
    public TaskContext(ConsoleSigningKey key,ConsoleUsers users){this.key=key;this.users=users;}
    public String issueUser(ConsolePrincipal actor,String agent,String env,String session,String text,String requestId,boolean evaluation,String namespace){
        if(evaluation || actor==null || actor.readOnly() || !"USER".equals(actor.mode())
            || actor.userId()==null || actor.userId().isBlank() || !"ADMIN".equals(actor.platformRole())
            || !"keel-assistant".equals(agent) || !"staging".equals(env))return null;
        var id=requestId==null || requestId.isBlank()?hash(actor.userId()+":"+session+":"+text):requestId;
        if(!id.matches("[A-Za-z0-9_-]{1,128}"))throw invalid("requestId格式无效");
        if(namespace==null || !namespace.matches("user|acceptance:[A-Za-z0-9_-]{1,64}"))throw invalid("任务数据空间无效");
        return sign("user",actor.userId(),Map.of("username",actor.username(),"session",hash(actor.userId()+":"+session),
            "text_hash",hash(text),"request_id",id,"namespace",namespace),300);
    }
    public String forRequestReplay(String token){
        var c=verify(token,"user");
        try{return sign("user",c.getSubject(),Map.of("username",c.getStringClaim("username"),
            "session",c.getStringClaim("session"),"text_hash",c.getStringClaim("text_hash"),
            "request_id",c.getStringClaim("request_id"),"namespace",c.getStringClaim("namespace"),"request_replay",true),300);
        }catch(java.text.ParseException invalid){throw denied();}
    }
    public String issueExecution(Map<String,Object> task){
        return sign("execution",String.valueOf(task.get("owner_user")),Map.of(
            "username",task.get("owner_username"),"task_id",task.get("task_id"),"fence",task.get("fence")),130);
    }
    private String sign(String purpose,String subject,Map<String,Object> values,int seconds){
        try{
            var now=Instant.now();var builder=new JWTClaimsSet.Builder().issuer("keel-assistant-task")
                .audience("keel-assistant-task").subject(subject).jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(seconds)))
                .claim("agent","keel-assistant").claim("env","staging").claim("task_type","analysis").claim("purpose",purpose);
            values.forEach(builder::claim);
            var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),builder.build());jwt.sign(new RSASSASigner(key.privateKey()));return jwt.serialize();
        }catch(Exception error){throw denied();}
    }
    public JWTClaimsSet verify(String token,String purpose){
        try{
            var jwt=SignedJWT.parse(token);var claims=jwt.getJWTClaimsSet();var now=Instant.now();
            if(!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new RSASSAVerifier(key.publicKey()))
                || !"keel-assistant-task".equals(claims.getIssuer()) || !claims.getAudience().equals(List.of("keel-assistant-task"))
                || !purpose.equals(claims.getStringClaim("purpose")) || !"keel-assistant".equals(claims.getStringClaim("agent"))
                || !"staging".equals(claims.getStringClaim("env")) || claims.getSubject()==null || claims.getSubject().isBlank()
                || !"analysis".equals(claims.getStringClaim("task_type"))
                || claims.getIssueTime()==null || claims.getIssueTime().toInstant().isAfter(now.plusSeconds(5))
                || claims.getExpirationTime()==null || !claims.getExpirationTime().toInstant().isAfter(now)
                || claims.getExpirationTime().toInstant().isAfter(claims.getIssueTime().toInstant().plusSeconds(300)))throw denied();
            if("user".equals(purpose) && (claims.getStringClaim("namespace")==null
                || !claims.getStringClaim("namespace").matches("user|acceptance:[A-Za-z0-9_-]{1,64}")
                || claims.getStringClaim("session")==null || claims.getStringClaim("text_hash")==null
                || claims.getStringClaim("request_id")==null))throw denied();
            requireUser(claims.getStringClaim("username"));return claims;
        }catch(Exception error){throw denied();}
    }
    public ConsoleUsers.ConsoleUser requireUser(String username){
        var user=users.find(username).orElseThrow(TaskContext::denied);
        if(!"ADMIN".equals(user.platformRole()))throw denied();return user;
    }
    public static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    public static KeelException denied(){return new KeelException(ErrorCode.TOOL_NOT_GRANTED,"任务缺少有效的当前用户执行授权");}
    public static KeelException invalid(String message){return new KeelException(ErrorCode.SERVER_INVALID_PARAM,message);}
}
