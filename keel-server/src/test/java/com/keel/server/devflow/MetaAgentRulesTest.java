package com.keel.server.devflow;

import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MetaAgentRulesTest {
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }
    @Test void requiresAnActualWritableAdministrator() {
        assertThatThrownBy(MetaAgentExecution::requireAdmin).isInstanceOf(KeelException.class);
        var request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        ConsolePrincipal.set(request, ConsolePrincipal.preview());
        assertThatThrownBy(MetaAgentExecution::requireAdmin).isInstanceOf(KeelException.class);
        ConsolePrincipal.set(request, ConsolePrincipal.service("meta-agent"));
        assertThatThrownBy(MetaAgentExecution::requireAdmin).isInstanceOf(KeelException.class);
        ConsolePrincipal.set(request, ConsolePrincipal.dev());
        assertThatCode(MetaAgentExecution::requireAdmin).doesNotThrowAnyException();
    }
    @Test void rejectsUnsupportedScopeAndFakeSeedUploads() {
        assertThatCode(() -> MetaAgentExecution.validate(draft("DEV", "CREATE", 0, 7.0))).doesNotThrowAnyException();
        for (var bad : List.of(draft("BIZ", "CREATE", 0, 7.0), draft("dev", "CREATE", 0, 7.0),
                draft("DEV", "CHANGE", 0, 7.0), draft("DEV", "CREATE", 34, 7.0),
                draft("DEV", "CREATE", 0, Double.NaN))) {
            assertThatThrownBy(() -> MetaAgentExecution.validate(bad)).isInstanceOf(KeelException.class);
        }
    }
    static DevflowTypes.Draft draft(String layer, String kind, int seeds, double budget) {
        return new DevflowTypes.Draft("QA", "qa-meta-loop", layer, kind, "COLLAB", "生成需求文档", "tool-agent", budget,
                seeds, List.of(), List.of(), "QA", "研发人员", "需求到文档", "包含目标", true);
    }
}
