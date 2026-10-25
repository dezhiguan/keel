package com.keel.server.registry.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.jdbc.core.JdbcTemplate;

class CareerMateRegistrarTest {
    @Test void restartsCannotOverwriteManagedVersionsOrRestoreLegacyProduction() {
        var statements = new java.util.ArrayList<String>();
        var jdbc = new JdbcTemplate() {
            @Override public int update(String sql, Object... args) {
                statements.add(sql);
                return 1;
            }
        };
        new CareerMateRegistrar(jdbc, "http://legacy:18080").run(null);
        assertThat(statements).hasSize(2).allSatisfy(sql -> assertThat(sql).doesNotContain("DO UPDATE"));
        assertThat(statements.get(1)).contains("WHERE NOT EXISTS", "WHERE agent_name = ?");
    }
    @Test void blankEndpointDoesNotRegister() {
        assertThat(CareerMateRegistrar.enabled(null)).isFalse();
        assertThat(CareerMateRegistrar.enabled("  ")).isFalse();
        assertThat(CareerMateRegistrar.enabled("http://careermate-backend.careermate.svc.cluster.local:18080")).isTrue();
    }

    @Test void manifestPointsAtTheConfiguredEndpoint() {
        var manifest = CareerMateRegistrar.manifest("http://careermate-backend.careermate.svc.cluster.local:18080");
        assertThat(manifest).contains("\"name\":\"careermate\"");
        assertThat(manifest).contains("\"language\":\"java\"");
        assertThat(manifest).contains("http://careermate-backend.careermate.svc.cluster.local:18080");
        assertThat(manifest).doesNotContain("sk-");
    }
}
