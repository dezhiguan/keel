package com.keel.server.tool.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.auth.*;
import com.keel.server.common.KeelException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
@Testcontainers(disabledWithoutDocker=true)
class EmployeeReadPersistenceTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    private JdbcTemplate db;private EmployeeReadService service;private String token;
    private final ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup(){
        db=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        db.execute("DROP TABLE IF EXISTS agent,agent_version,agent_employee_read_scope");
        db.execute("CREATE TABLE agent(name text,display_name text,status text,kind text,owner_org text)");
        db.execute("CREATE TABLE agent_version(id bigserial,agent_name text,env text,version text,manifest_json jsonb,released_at timestamptz)");
        db.execute("CREATE TABLE agent_employee_read_scope(agent_name text,environment text,target_org text,enabled boolean)");
        db.execute("INSERT INTO agent VALUES ('employee-a','员工A','ONLINE','AGENT','平台组'),('employee-b','员工B','OFFLINE','AGENT','平台组'),('private-employee','私人','ONLINE','AGENT','other')");
        db.execute("INSERT INTO agent_version(agent_name,env,version,manifest_json,released_at) VALUES ('employee-a','staging','v2','{\"metadata\":{\"description\":\"测试职责\"},\"secret\":\"DO_NOT_RETURN\"}',now()),('employee-b','staging','v1','{}',now()),('private-employee','staging','v8','{}',now()),('employee-a','dev','v3','{}',now())");
        db.execute("INSERT INTO agent_employee_read_scope VALUES ('assistant','staging','平台组',true)");
        var contexts=new UserReadContext(new ConsoleSigningKey(),new ConsoleUsers("tester"));
        token=contexts.issue(new ConsolePrincipal("USER","54","tester","测试","平台组","ADMIN",List.of(),false),"assistant","staging","s");
        service=new EmployeeReadService(db,contexts);
    }
    private com.fasterxml.jackson.databind.node.ObjectNode args(String env){return json.createObjectNode().put("env",env).put("_authorization",token).put("_session",UserReadContext.sessionHash("54","s"));}
    @Test void paginationAndOrganizationScopePreserveTruthAndNeverExposeSecrets(){
        var result=service.read("keel.employees.list","assistant","staging",args("staging").put("size",1));
        assertThat(result.get("total")).isEqualTo(2L);assertThat(result.get("complete")).isEqualTo(false);assertThat(result.get("hasMore")).isEqualTo(true);
        assertThat(result.toString()).contains("employee-a","unknown").doesNotContain("private-employee","DO_NOT_RETURN","manifest_json");
        var second=service.read("keel.employees.list","assistant","staging",args("staging").put("size",1).put("page",2));
        assertThat(second.toString()).contains("employee-b").doesNotContain("employee-a");
    }
    @Test void detailsUseExactEnvironmentAndRejectUnapprovedEnvironmentAndUnknownId(){
        var result=service.read("keel.employees.detail","assistant","staging",args("staging").put("id","employee-a"));
        assertThat(result.toString()).contains("v2").doesNotContain("v3");
        assertThatThrownBy(()->service.read("keel.employees.detail","assistant","staging",args("dev").put("id","employee-a"))).isInstanceOf(KeelException.class);
        assertThatThrownBy(()->service.read("keel.employees.detail","assistant","staging",args("staging").put("id","private-employee"))).isInstanceOf(KeelException.class);
    }
}
