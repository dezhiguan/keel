package com.keel.server.tool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void readsToolsDeclaredOnAManifest() {
        var declared = ToolRegistryService.declaredTools(json, """
                {"spec":{"tools":[
                  {"name":"alarm_query","description":"查告警","risk":"low","access":"read"},
                  {"name":"work_order_create","shared":true,"risk":"high","access":"write","version":"v2"}
                ]}}
                """);
        assertThat(declared).extracting(ToolRegistryService.DeclaredTool::name)
                .containsExactly("alarm_query", "work_order_create");
        assertThat(declared.get(1).scope()).isEqualTo("SHARED");
        assertThat(declared.get(1).risk()).isEqualTo("HIGH");
        assertThat(declared.get(1).access()).isEqualTo("WRITE");
        assertThat(declared.get(0).description()).isEqualTo("查告警");
    }

    @Test void prodDependentBlocksRetire() {
        assertThat(ToolRegistryService.blocksRetire(List.of("dev", "prod"))).isTrue();
        assertThat(ToolRegistryService.blocksRetire(List.of("dev"))).isFalse();
    }
}
