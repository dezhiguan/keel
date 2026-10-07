package com.keel.server.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 控制台允许进入的账号。用户名只从环境变量读取，不写进代码。 */
@Component
public class ConsoleUsers {
    private static final Logger log = LoggerFactory.getLogger(ConsoleUsers.class);

    private final ConsoleUser user;

    public ConsoleUsers(@Value("${KEEL_CONSOLE_USERNAME:}") String username) {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty()) {
            this.user = null;
            log.warn("KEEL_CONSOLE_USERNAME 未配置，控制台账号密码登录不会放行");
            return;
        }
        this.user = new ConsoleUser(name, "官德志", "ADMIN", "平台组");
    }

    public Optional<ConsoleUser> find(String username) {
        if (user == null || username == null || !user.username().equalsIgnoreCase(username.trim())) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    public Optional<ConsoleUser> only() {
        return Optional.ofNullable(user);
    }

    public record ConsoleUser(String username, String displayName, String platformRole, String org) {}
}
