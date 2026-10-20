package com.keel.server.auth;
import com.keel.server.common.KeelException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class UserReadContextTest {
    private final ConsoleSigningKey key=new ConsoleSigningKey();
    private final UserReadContext contexts=new UserReadContext(key,new ConsoleUsers("tester"));
    private ConsolePrincipal user(){return new ConsolePrincipal("USER","54","tester","测试","平台组","ADMIN",List.of(),false);}
    @Test void trustedUserCanReadOnlyAsBoundAgentAndEnvironment(){
        var token=contexts.issue(user(),"assistant","staging","session-a");
        var actor=contexts.verify(token,"assistant","staging");
        assertThat(actor.userId()).isEqualTo("54");assertThat(actor.session()).isEqualTo(UserReadContext.sessionHash("54","session-a"));
        assertThatThrownBy(()->contexts.verify(token,"other","staging")).isInstanceOf(KeelException.class);
        assertThatThrownBy(()->contexts.verify(token,"assistant","dev")).isInstanceOf(KeelException.class);
    }
    @Test void rejectsMissingTamperedAndOtherPlatformSignatures(){
        assertThatThrownBy(()->contexts.verify("","assistant","staging")).isInstanceOf(KeelException.class);
        var other=new UserReadContext(new ConsoleSigningKey(),new ConsoleUsers("tester"));
        var token=other.issue(user(),"assistant","staging","s");
        assertThatThrownBy(()->contexts.verify(token,"assistant","staging")).isInstanceOf(KeelException.class);
    }
    @Test void serviceAndPreviewCannotSelfAuthorizeAndProductionIsNotIssued(){
        assertThat(contexts.issue(ConsolePrincipal.service("assistant"),"assistant","staging","s")).isNull();
        assertThat(contexts.issue(ConsolePrincipal.preview(),"assistant","staging","s")).isNull();
        assertThat(contexts.issue(user(),"assistant","prod","s")).isNull();
    }
    @Test void expiredOrOverlongReadContextsAreRejected() throws Exception {
        for(var expiry:java.util.List.of(-1L,700L)) {
            var now=java.time.Instant.now();
            var claims=new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer("keel-read-context").audience("keel-employee-read")
                .subject("54").issueTime(java.util.Date.from(now.minusSeconds(10))).expirationTime(java.util.Date.from(now.plusSeconds(expiry)))
                .claim("agent","assistant").claim("env","staging").claim("username","tester").build();
            var jwt=new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.RS256),claims);
            jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(key.privateKey()));
            assertThatThrownBy(()->contexts.verify(jwt.serialize(),"assistant","staging")).isInstanceOf(KeelException.class);
        }
    }
    @Test void removedUserLosesAccessBeforeTokenExpires(){
        var token=contexts.issue(user(),"assistant","staging","s");
        var removed=new UserReadContext(key,new ConsoleUsers("different"));
        assertThatThrownBy(()->removed.verify(token,"assistant","staging")).isInstanceOf(KeelException.class);
    }
}
