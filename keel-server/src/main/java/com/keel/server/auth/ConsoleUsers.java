package com.keel.server.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;

/** 控制台允许进入的账号。用户名只从环境变量读取，不写进代码。 */
@Component
public class ConsoleUsers {
    private static final Logger log = LoggerFactory.getLogger(ConsoleUsers.class);

    private final ConsoleUser user;
    private final Map<String,ConsoleUser> accounts;

    public ConsoleUsers(String username) { this(username,"[]"); }

    @Autowired
    public ConsoleUsers(@Value("${KEEL_CONSOLE_USERNAME:}") String username,
                        @Value("${KEEL_CONSOLE_ADDITIONAL_USERS:[]}") String additionalUsers) {
        String name = username == null ? "" : username.trim();
        var allowed=new LinkedHashMap<String,ConsoleUser>();
        if (name.isEmpty()) {
            this.user = null;
            log.warn("KEEL_CONSOLE_USERNAME 未配置，控制台账号密码登录不会放行");
        } else {
            this.user = new ConsoleUser(name, "官德志", "ADMIN", "平台组");
            allowed.put(name.toLowerCase(Locale.ROOT),this.user);
        }
        try {
            var rows=new ObjectMapper().readTree(additionalUsers);
            if(!rows.isArray() || rows.size()>32)throw new IllegalArgumentException();
            for(var row:rows) {
                var account=row.path("username").asText().trim();
                var display=row.path("displayName").asText().trim();var org=row.path("org").asText().trim();
                if(!account.matches("[A-Za-z0-9_\\u4e00-\\u9fa5]{2,32}") || display.isEmpty() || org.isEmpty()
                        || display.length()>100 || org.length()>100 || !"VIEWER".equals(row.path("platformRole").asText())
                        || allowed.containsKey(account.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException();
                allowed.put(account.toLowerCase(Locale.ROOT),new ConsoleUser(account,display,"VIEWER",org));
            }
        } catch(Exception error) {throw new IllegalArgumentException("Invalid explicit console account allowlist");}
        this.accounts=Map.copyOf(allowed);
    }

    public Optional<ConsoleUser> find(String username) {
        return username==null?Optional.empty():Optional.ofNullable(accounts.get(username.trim().toLowerCase(Locale.ROOT)));
    }

    public Optional<ConsoleUser> only() {
        return Optional.ofNullable(user);
    }

    public record ConsoleUser(String username, String displayName, String platformRole, String org) {}
}
