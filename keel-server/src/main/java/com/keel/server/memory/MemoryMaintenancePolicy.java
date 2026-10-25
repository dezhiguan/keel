package com.keel.server.memory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Arrays;
@Component
public class MemoryMaintenancePolicy {
    private final String enabled;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public MemoryMaintenancePolicy(@Value("${KEEL_MEMORY_ASYNC_AGENTS:}") String enabled,org.springframework.jdbc.core.JdbcTemplate jdbc){this.enabled=enabled;this.jdbc=jdbc;}
    public boolean enabled(String agent,String env){return Arrays.stream(enabled.split(",")).map(String::trim).anyMatch((agent+":"+env)::equals);}
    public boolean enabledSession(String agent,String env,String hash){
        if(!enabled(agent,env))return false;
        var modes=jdbc.query("SELECT memory_maintenance_mode FROM agent_session WHERE agent_name=? AND env=? AND encode(sha256(convert_to(owner_user||':'||id,'UTF8')),'hex')=?",(rs,n)->rs.getString(1),agent,env,hash);
        return !modes.isEmpty() && !"sync".equals(modes.getFirst());
    }
}
