package com.keel.server.auth;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Narrow, short-lived delegation, never a console access token. */
@Service
public class UserReadContext {
    private static final String ISSUER="keel-read-context", AUDIENCE="keel-employee-read";
    private final ConsoleSigningKey key;
    private final ConsoleUsers users;
    public UserReadContext(ConsoleSigningKey key, ConsoleUsers users) {this.key=key;this.users=users;}
    public String issue(ConsolePrincipal user,String agent,String env,String session) {
        if(user==null || user.readOnly() || !"USER".equals(user.mode()) || user.userId()==null || user.userId().isBlank()
                || !"ADMIN".equals(user.platformRole()) || !java.util.Set.of("dev","staging").contains(env)) return null;
        try {
            var now=Instant.now();
            var claims=new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject(user.userId())
                    .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                    .jwtID(UUID.randomUUID().toString()).claim("agent",agent).claim("env",env)
                    .claim("session",sessionHash(user.userId(),session)).claim("username",user.username()).build();
            var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),claims);
            jwt.sign(new RSASSASigner(key.privateKey()));return jwt.serialize();
        }catch(Exception error){throw denied();}
    }
    public static String sessionHash(String user,String session){
        try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((user+":"+session).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(Exception error){throw denied();}
    }
    public Actor verify(String token,String agent,String env) {
        try {
            var jwt=SignedJWT.parse(token);var c=jwt.getJWTClaimsSet();var now=Instant.now();
            if(!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new RSASSAVerifier(key.publicKey()))
                || !ISSUER.equals(c.getIssuer()) || !c.getAudience().equals(java.util.List.of(AUDIENCE))
                || c.getExpirationTime()==null || !c.getExpirationTime().toInstant().isAfter(now)
                || c.getIssueTime()==null || c.getIssueTime().toInstant().isAfter(now.plusSeconds(5))
                || c.getExpirationTime().toInstant().isAfter(c.getIssueTime().toInstant().plusSeconds(300))
                || !agent.equals(c.getStringClaim("agent")) || !env.equals(c.getStringClaim("env"))
                || c.getSubject()==null || c.getSubject().isBlank())throw denied();
            var account=users.find(c.getStringClaim("username")).orElseThrow(UserReadContext::denied);
            if(!"ADMIN".equals(account.platformRole()))throw denied();
            return new Actor(c.getSubject(),account.username(),account.org(),c.getStringClaim("session"));
        }catch(Exception error){throw denied();}
    }
    public record Actor(String userId,String username,String org,String session){}
    private static KeelException denied(){return new KeelException(ErrorCode.TOOL_NOT_GRANTED,"只读查询缺少有效用户授权");}
}
