package com.keel.server.cost;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CostPoliciesTest {
    static class Database extends JdbcTemplate {
        final Map<String,String> layers=new HashMap<>();
        @Override public List<Map<String,Object>> queryForList(String sql,Object... args){
            String config=layers.get(args[0]+":"+args[1]);
            return config==null?List.of():List.of(Map.of("revision",1,"config",config));
        }
    }
    @Test void inheritsFeaturesButCannotRaisePlatformBudget(){
        var db=new Database();
        db.layers.put("platform:*","{\"mode\":\"observe\",\"maxCostCny\":1,\"maxCalls\":32,\"contextDedupe\":false}");
        db.layers.put("agent:a:staging","{\"mode\":\"enforce\",\"maxCostCny\":0.2,\"maxCalls\":8,\"contextDedupe\":true}");
        var policy=new CostPolicies(db,new ObjectMapper()).effective("a","staging","org");
        assertThat(policy.path("contextDedupe").asBoolean()).isTrue();assertThat(policy.path("version").asText()).contains("agent:a:staging@1");
        db.layers.put("agent:a:staging","{\"maxCostCny\":2}");
        assertThatThrownBy(()->new CostPolicies(db,new ObjectMapper()).effective("a","staging","org")).isInstanceOf(com.keel.server.common.KeelException.class);
    }
    @Test void organizationAndEnvironmentEnforcementCannotBeRelaxed(){
        var db=new Database();
        db.layers.put("platform:*","{\"mode\":\"observe\",\"maxCostCny\":1,\"maxCalls\":32}");
        db.layers.put("org:org","{\"mode\":\"enforce\",\"maxCostCny\":0.2}");
        db.layers.put("env:staging","{\"mode\":\"observe\"}");
        assertThatThrownBy(()->new CostPolicies(db,new ObjectMapper()).effective("a","staging","org")).isInstanceOf(com.keel.server.common.KeelException.class);
    }
}
