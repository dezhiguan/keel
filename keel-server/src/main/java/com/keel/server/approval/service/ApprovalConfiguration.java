package com.keel.server.approval.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ApprovalConfiguration {
    @Bean
    Clock keelClock() {
        return Clock.systemUTC();
    }
}
