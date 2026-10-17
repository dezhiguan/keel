package com.keel.server.tool.controller;

import com.keel.server.common.KeelException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ToolInvokeEnvironmentTest {
    @Test void registeredEnvironmentsRemainIndependent() {
        assertThat(ToolInvokeController.resourceEnvironment("staging", true, false)).isEqualTo("staging");
        assertThat(ToolInvokeController.resourceEnvironment("prod", true, false)).isEqualTo("prod");
    }

    @Test void onlyApprovedProdPreviewMayUseStagingCapabilitiesBeforeProdRegistration() {
        assertThatThrownBy(() -> ToolInvokeController.resourceEnvironment("prod", false, false))
                .isInstanceOf(KeelException.class);
        assertThat(ToolInvokeController.resourceEnvironment("prod", false, true)).isEqualTo("staging");
        assertThatThrownBy(() -> ToolInvokeController.resourceEnvironment("staging", false, true))
                .isInstanceOf(KeelException.class);
    }
}
