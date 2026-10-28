package com.keel.server.auth;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ConsoleUsersTest {
    private final String extra="[{\"username\":\"qa_reader\",\"displayName\":\"QA\",\"platformRole\":\"VIEWER\",\"org\":\"平台组\"}]";
    @Test void onlyExplicitAdditionalViewerIsAdmitted(){
        var users=new ConsoleUsers("owner",extra);
        assertThat(users.only().orElseThrow().username()).isEqualTo("owner");
        assertThat(users.find("QA_READER").orElseThrow().platformRole()).isEqualTo("VIEWER");
        assertThat(users.find("unlisted")).isEmpty();
        assertThat(new ConsoleUsers("owner").find("qa_reader")).isEmpty();
    }
    @Test void duplicatesAndUnexpectedRolesFailClosed(){
        assertThatThrownBy(()->new ConsoleUsers("qa_reader",extra)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ConsoleUsers("owner",extra.replace("VIEWER","ADMIN"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ConsoleUsers("owner","{}")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void viewerCanOnlyPostOwnAssistantReadsAndIndependentConsent(){
        assertThat(ConsoleSessionFilter.viewerAllowed("POST","/api/v1/agents/keel-assistant/chat/stream")).isTrue();
        assertThat(ConsoleSessionFilter.viewerAllowed("POST","/api/v1/eval/runs/ev_"+"a".repeat(32)+"/participants")).isTrue();
        for(var path:java.util.List.of("/api/v1/agents/other/chat/stream","/api/v1/agents/keel-assistant/release",
                "/api/v1/cost-control/policies","/api/v1/eval/keel-assistant/runs"))
            assertThat(ConsoleSessionFilter.viewerAllowed("POST",path)).isFalse();
        assertThat(ConsoleSessionFilter.viewerAllowed("DELETE","/api/v1/agents/keel-assistant")).isFalse();
        assertThat(ConsoleSessionFilter.viewerAllowed("GET","/api/v1/me")).isTrue();
        assertThat(ConsoleSessionFilter.viewerAllowed("GET","/api/v1/agents/other/sessions")).isFalse();
        assertThat(ConsoleSessionFilter.viewerAllowed("GET","/api/v1/insight/traces")).isFalse();
    }
    @Test void viewerReadTicketCannotExpandAgentEnvironmentOrBorrowSession(){
        var users=new ConsoleUsers("owner",extra);var contexts=new UserReadContext(new ConsoleSigningKey(),users);
        var actor=new ConsolePrincipal("USER","user-b","qa_reader","QA","平台组","VIEWER",java.util.List.of(),false);
        var token=contexts.issue(actor,"keel-assistant","staging","b-session");assertThat(token).isNotNull();
        var verified=contexts.verify(token,"keel-assistant","staging");
        assertThat(verified.userId()).isEqualTo("user-b");
        assertThat(verified.session()).isNotEqualTo(UserReadContext.sessionHash("owner","b-session"));
        assertThat(contexts.issue(actor,"other","staging","b-session")).isNull();
        assertThat(contexts.issue(actor,"keel-assistant","prod","b-session")).isNull();
        assertThat(contexts.issue(actor,"keel-assistant","dev","b-session")).isNull();
    }
}
