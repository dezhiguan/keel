package com.keel.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "keel.console")
public class ConsoleAuthProperties {
    private final Auth auth = new Auth();
    private final Preview preview = new Preview();

    public Auth getAuth() { return auth; }

    public Preview getPreview() { return preview; }

    public String mode() { return auth.mode; }

    public boolean openForTests() { return auth.openForTests; }

    public Boolean previewEnabled() { return preview.enabled; }

    public boolean previewOn() { return Boolean.TRUE.equals(preview.enabled); }

    public static class Auth {
        private String mode;
        private String localPassword = "";
        private String localSmsCode = "";
        /** 2026-10-07 读 auth-gateway AuthProperties 的默认值。 */
        private String assertionAudience = "https://auth.careermate.cn/oauth/token";
        private String issuer = "https://auth.careermate.cn";
        private String clientId = "keel-console-backend";
        private boolean openForTests;

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getLocalPassword() { return localPassword; }
        public void setLocalPassword(String localPassword) { this.localPassword = localPassword; }
        public String getLocalSmsCode() { return localSmsCode; }
        public void setLocalSmsCode(String localSmsCode) { this.localSmsCode = localSmsCode; }
        public String getAssertionAudience() { return assertionAudience; }
        public void setAssertionAudience(String assertionAudience) { this.assertionAudience = assertionAudience; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
        public boolean isOpenForTests() { return openForTests; }
        public void setOpenForTests(boolean openForTests) { this.openForTests = openForTests; }
    }

    public static class Preview {
        private Boolean enabled;

        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    }
}
