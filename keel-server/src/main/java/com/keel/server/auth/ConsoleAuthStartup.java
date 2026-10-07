package com.keel.server.auth;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ConsoleAuthStartup {
    public ConsoleAuthStartup(Environment environment, ConsoleAuthProperties properties) {
        ConsoleAuthRules.check(properties.mode(), properties.previewEnabled(),
                environment.matchesProfiles("local"), properties.openForTests());
    }
}
