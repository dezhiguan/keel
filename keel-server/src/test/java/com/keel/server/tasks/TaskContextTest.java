package com.keel.server.tasks;

import com.keel.server.auth.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class TaskContextTest {
    private final ConsoleSigningKey key=new ConsoleSigningKey();
    private final ConsoleUsers users=new ConsoleUsers("fixture-user");
    private final TaskContext contexts=new TaskContext(key,users);
    private final ConsolePrincipal user=new ConsolePrincipal("USER","fixture-subject","fixture-user","fixture","平台组","ADMIN",List.of(),false);
    @Test void userContextBindsAuthenticatedIdentityMessageSessionAndRequest()throws Exception{
        var token=contexts.issueUser(user,"keel-assistant","staging","session","请生成分析报告","request-1",false,"user");
        var c=contexts.verify(token,"user");
        assertThat(c.getSubject()).isEqualTo("fixture-subject");
        assertThat(c.getStringClaim("session")).isEqualTo(TaskContext.hash("fixture-subject:session"));
        assertThat(c.getStringClaim("text_hash")).isEqualTo(TaskContext.hash("请生成分析报告"));
        assertThat(c.getStringClaim("request_id")).isEqualTo("request-1");
        assertThatThrownBy(()->contexts.verify(token,"execution")).isInstanceOf(RuntimeException.class);
    }
    @Test void servicePreviewOtherAgentProdAndEvalCannotMintUserExecutionContexts(){
        assertThat(contexts.issueUser(ConsolePrincipal.service("keel-assistant"),"keel-assistant","staging","s","x","r",false,"user")).isNull();
        assertThat(contexts.issueUser(ConsolePrincipal.preview(),"keel-assistant","staging","s","x","r",false,"user")).isNull();
        assertThat(contexts.issueUser(user,"other-agent","staging","s","x","r",false,"user")).isNull();
        assertThat(contexts.issueUser(user,"keel-assistant","prod","s","x","r",false,"user")).isNull();
        assertThat(contexts.issueUser(user,"keel-assistant","staging","s","x","r",true,"user")).isNull();
    }
    @Test void oldReadTicketAndForgedTicketDoNotAuthorizeTasks(){
        var read=new UserReadContext(key,users).issue(user,"keel-assistant","staging","s");
        assertThatThrownBy(()->contexts.verify(read,"user")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->contexts.verify("forged-user-context","user")).isInstanceOf(RuntimeException.class);
    }
    @org.junit.jupiter.api.Test void replayHintIsSignedWithoutBroadeningAuthority()throws Exception{
        var contexts=new TaskContext(new ConsoleSigningKey(),new ConsoleUsers("fixture-user"));
        var user=new ConsolePrincipal("USER","fixture-a","fixture-user","fixture","平台组","ADMIN",java.util.List.of(),false);
        var original=contexts.issueUser(user,"keel-assistant","staging","s","生成报告","r",false,"user");
        var normal=contexts.verify(original,"user");var marked=contexts.verify(contexts.forRequestReplay(original),"user");
        for(String claim:java.util.List.of("sub","agent","env","task_type","purpose","username","session","text_hash","request_id","namespace"))
            assertThat(marked.getClaim(claim)).isEqualTo(normal.getClaim(claim));
        assertThat(marked.getBooleanClaim("request_replay")).isTrue();
        assertThatThrownBy(()->contexts.forRequestReplay("forged")).isInstanceOf(com.keel.server.common.KeelException.class);
    }
}
