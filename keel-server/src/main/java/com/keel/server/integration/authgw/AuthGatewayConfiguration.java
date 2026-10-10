package com.keel.server.integration.authgw;

import com.keel.server.auth.ConsoleAuthProperties;
import com.keel.server.auth.ConsoleSigningKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthGatewayConfiguration {
    @Bean
    AuthGatewayClient authGatewayClient(ConsoleAuthProperties properties, ConsoleSigningKey key) {
        var auth = properties.getAuth();
        return new AuthGatewayClient(System.getenv("KEEL_AUTH_GATEWAY_URL"),
                auth.getClientId(), key.privatePem(), key.kid(), auth.getAssertionAudience());
    }

    @Bean
    AuthGatewayLoginClient authGatewayLoginClient() {
        return new AuthGatewayLoginClient(System.getenv("KEEL_AUTH_GATEWAY_URL"));
    }
}
