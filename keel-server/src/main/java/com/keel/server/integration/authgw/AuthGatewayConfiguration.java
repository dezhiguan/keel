package com.keel.server.integration.authgw;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthGatewayConfiguration {
    @Bean
    AuthGatewayClient authGatewayClient() {
        return new AuthGatewayClient(System.getenv("KEEL_AUTH_GATEWAY_URL"));
    }

    @Bean
    AuthGatewayLoginClient authGatewayLoginClient() {
        return new AuthGatewayLoginClient(System.getenv("KEEL_AUTH_GATEWAY_URL"));
    }
}
