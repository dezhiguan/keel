package com.keel.server.prompt;

import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class PromptActorTest {
    @Test void usesAuthenticatedIdentityAndDoesNotMakeAnOrdinaryUserAdmin() {
        var request=new MockHttpServletRequest();
        ConsolePrincipal.set(request,new ConsolePrincipal("USER","id-42","qa-user","验收用户","QA","DEVELOPER",List.of(),false));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            var actor=PromptCatalog.Actor.current();
            assertThat(actor.userId()).isEqualTo("qa-user");
            assertThat(actor.org()).isEqualTo("QA");
            assertThat(actor.admin()).isFalse();
        } finally {RequestContextHolder.resetRequestAttributes();}
        assertThatThrownBy(PromptCatalog.Actor::current).isInstanceOf(KeelException.class);
    }
}
