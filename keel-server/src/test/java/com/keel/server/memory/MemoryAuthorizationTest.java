package com.keel.server.memory;

import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryAuthorizationTest {
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

    @Test void previewAndOtherOrganizationCannotReadUserMemory() {
        principal(ConsolePrincipal.preview());
        assertThatThrownBy(() -> MemoryService.authorize("careermate", "hr", "", false))
                .isInstanceOf(KeelException.class);
        principal(new ConsolePrincipal("USER", "u_2", "bob", "Bob", "sales", "OWNER", List.of(), false));
        assertThatThrownBy(() -> MemoryService.authorize("careermate", "hr", "", false))
                .isInstanceOf(KeelException.class);
        assertThatCode(() -> MemoryService.authorize("careermate", "hr", "u_2", false)).doesNotThrowAnyException();
    }

    @Test void agentServiceCanOnlyReadItsOwnUsersAndItsOwnAgent() {
        principal(ConsolePrincipal.service("careermate"));
        assertThatThrownBy(() -> MemoryService.authorize("careermate", "hr", "", false))
                .isInstanceOf(KeelException.class);
        assertThatThrownBy(() -> MemoryService.authorize("askdb", "hr", "u_1", false))
                .isInstanceOf(KeelException.class);
        assertThatCode(() -> MemoryService.authorize("careermate", "hr", "u_1", false)).doesNotThrowAnyException();
    }

    private static void principal(ConsolePrincipal principal) {
        var request = new MockHttpServletRequest();
        ConsolePrincipal.set(request, principal);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
