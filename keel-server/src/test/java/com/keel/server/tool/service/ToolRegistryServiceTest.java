package com.keel.server.tool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Test void breakingVersionMustUseANewName() {
        assertThat(ToolRegistryService.breakingRejection(true)).contains("新名字");
        assertThat(ToolRegistryService.breakingRejection(false)).isNull();
    }

    @Test void visibleOnlyWhenDeclaredOrOwnedInThatEnv() {
        assertThat(ToolRegistryService.visibleInEnv("all", "echo", null, Map.of(), Set.of())).isTrue();
        assertThat(ToolRegistryService.visibleInEnv("prod", "echo", null, Map.of("echo", 1), Set.of())).isTrue();
        assertThat(ToolRegistryService.visibleInEnv("test", "echo", "askdb", Map.of(), Set.of("askdb"))).isTrue();
        assertThat(ToolRegistryService.visibleInEnv("dev", "echo", "askdb", Map.of(), Set.of("wind"))).isFalse();
        assertThat(ToolRegistryService.visibleInEnv(null, "echo", null, Map.of(), Set.of())).isTrue();
    }
}
