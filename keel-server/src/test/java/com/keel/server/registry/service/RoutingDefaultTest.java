package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.registry.mapper.AgentVersionMapper;
import com.keel.server.registry.model.entity.AgentVersion;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RoutingDefaultTest {
    @Test void defaultIsReadFromLatestRegistrationInExactKeyEnvironment() {
        var prod=version("prod","prod-chat");var staging=version("staging","staging-chat");
        var old=version("staging","old-chat");
        var mapper=(AgentVersionMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{AgentVersionMapper.class},
                (proxy,method,args)-> {if(method.getName().equals("listByAgent"))return List.of(prod,staging,old);throw new UnsupportedOperationException();});
        var registry=new AgentRegistryService(null,mapper,null,null,new ObjectMapper(),null);
        assertThat(registry.defaultModelForEnv("qa-default","staging")).isEqualTo("staging-chat");
        assertThat(registry.defaultModelForEnv("qa-default","prod")).isEqualTo("prod-chat");
        assertThat(registry.defaultModelForEnv("qa-default","dev")).isEmpty();
    }
    private AgentVersion version(String env,String model) {
        var v=new AgentVersion();v.setEnv(env);v.setManifestJson("{\"spec\":{\"models\":{\"default\":\""+model+"\"}}}");return v;
    }
}
