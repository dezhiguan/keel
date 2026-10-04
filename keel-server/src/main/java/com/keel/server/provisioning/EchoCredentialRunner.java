package com.keel.server.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Create the echo virtual key before the agent process starts mounting it. */
@Component
public class EchoCredentialRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(EchoCredentialRunner.class);
    private final EchoCredentialWriter writer;

    public EchoCredentialRunner(EchoCredentialWriter writer) {
        this.writer = writer;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            writer.ensure("echo");
        } catch (RuntimeException e) {
            log.warn("echo virtual key was not created: {}", e.getMessage());
        }
    }
}
