package com.keel.server.tool.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.auth.*;
import com.keel.server.common.KeelException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class EmployeeReadServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    private final UserReadContext ctx=new UserReadContext(new ConsoleSigningKey(),new ConsoleUsers("tester"));
    // No datasource: any accidental database access fails with a different exception.
    private final EmployeeReadService service=new EmployeeReadService(new JdbcTemplate(),ctx);
    @Test void noUserContextNeverReachesTheDatabase() throws Exception {
        assertThatThrownBy(()->service.read("keel.employees.list","a","staging",json.readTree("{\"env\":\"staging\"}"))).isInstanceOf(KeelException.class);
    }
    @Test void rejectsProductionAdditionalIdentityAndWrongSessionBeforeDatabase() throws Exception {
        var user=new ConsolePrincipal("USER","54","tester","测试","平台组","ADMIN",List.of(),false);
        var token=ctx.issue(user,"a","staging","s");
        for(var data:new String[]{"{\"env\":\"prod\"}","{\"env\":\"staging\",\"user_id\":\"admin\"}"}) {
            var args=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(data);
            args.put("_authorization",token);args.put("_session",UserReadContext.sessionHash("54","s"));
            assertThatThrownBy(()->service.read("keel.employees.list","a","staging",args)).isInstanceOf(KeelException.class);
        }
        var args=json.createObjectNode().put("env","staging").put("_authorization",token).put("_session","wrong");
        assertThatThrownBy(()->service.read("keel.employees.list","a","staging",args)).isInstanceOf(KeelException.class);
    }
    @Test void validatesPaginationTypesAndBounds() throws Exception {
        assertThat(EmployeeReadService.integer(json.readTree("{}"),"page",1,1,1000)).isEqualTo(1);
        for(var payload:new String[]{"{\"page\":0}","{\"page\":\"1\"}","{\"page\":1001}","{\"page\":1.5}"})
            assertThatThrownBy(()->EmployeeReadService.integer(json.readTree(payload),"page",1,1,1000)).isInstanceOf(KeelException.class);
    }
}
