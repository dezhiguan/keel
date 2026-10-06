package com.keel.server.archunit;

import com.keel.server.controller.archunit.violations.TopLevelControllerViolation;
import com.keel.server.insight.archunit.violations.InsightWriteViolation;
import com.keel.server.registry.archunit.violations.CrossModuleMapperViolation;
import com.keel.server.tool.archunit.violations.DirectHttpClientViolation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureTest {
    private static final Set<String> BUSINESS_MODULES = Set.of(
            "registry", "provisioning", "discovery", "tool", "approval", "release", "insight", "prompt");
    private static final String PREFIX = "com.keel.server.";

    private static final ArchCondition<JavaClass> NO_CROSS_MODULE_MAPPER = new ArchCondition<>("not access another business module's mapper") {
        @Override public void check(JavaClass origin, com.tngtech.archunit.lang.ConditionEvents events) {
            String source = moduleOf(origin.getPackageName());
            if (!BUSINESS_MODULES.contains(source)) return;
            origin.getDirectDependenciesFromSelf().forEach(dependency -> {
                JavaClass target = dependency.getTargetClass();
                String destination = moduleOf(target.getPackageName());
                if (BUSINESS_MODULES.contains(destination) && !source.equals(destination)
                        && target.getPackageName().contains(".mapper")) {
                    events.add(SimpleConditionEvent.violated(origin, origin.getName() + " depends on " + target.getName()));
                }
            });
        }
    };

    private static final ArchCondition<JavaClass> NO_TOP_LEVEL_TECHNICAL_PACKAGE = new ArchCondition<>("not use a top-level controller, service or dao package") {
        @Override public void check(JavaClass clazz, com.tngtech.archunit.lang.ConditionEvents events) {
            if (Set.of("controller", "service", "dao").contains(moduleOf(clazz.getPackageName()))) {
                events.add(SimpleConditionEvent.violated(clazz, clazz.getName() + " is in a top-level technical package"));
            }
        }
    };

    private static final ArchCondition<JavaClass> INSIGHT_READ_ONLY = new ArchCondition<>("not write business tables from insight") {
        @Override public void check(JavaClass clazz, com.tngtech.archunit.lang.ConditionEvents events) {
            if (!"insight".equals(moduleOf(clazz.getPackageName()))) return;
            clazz.getMethodCallsFromSelf().forEach(call -> {
                String owner = call.getTarget().getOwner().getName();
                String method = call.getTarget().getName();
                boolean jdbcWrite = owner.startsWith("org.springframework.jdbc.")
                        && Set.of("update", "batchUpdate", "execute").contains(method);
                boolean statementWrite = owner.equals("java.sql.Statement")
                        && Set.of("executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch").contains(method);
                boolean mapperWrite = owner.startsWith("com.baomidou.mybatisplus.")
                        && (method.startsWith("insert") || method.startsWith("update") || method.startsWith("delete"));
                boolean businessMapperWrite = owner.startsWith(PREFIX) && owner.contains(".mapper.")
                        && (method.startsWith("insert") || method.startsWith("update") || method.startsWith("delete"));
                if (jdbcWrite || statementWrite || mapperWrite || businessMapperWrite) {
                    events.add(SimpleConditionEvent.violated(clazz, clazz.getName() + " calls write method " + call.getTarget()));
                }
            });
        }
    };

    private static final ArchCondition<JavaClass> HTTP_THROUGH_INTEGRATION = new ArchCondition<>("create HTTP clients only in integration") {
        @Override public void check(JavaClass clazz, com.tngtech.archunit.lang.ConditionEvents events) {
            String module = moduleOf(clazz.getPackageName());
            if (!BUSINESS_MODULES.contains(module)) return;
            clazz.getMethodCallsFromSelf().forEach(call -> {
                String owner = call.getTarget().getOwner().getName();
                String method = call.getTarget().getName();
                if ((owner.equals("java.net.http.HttpClient") && method.equals("newHttpClient"))
                        || (owner.equals("org.springframework.web.reactive.function.client.WebClient") && Set.of("create", "builder").contains(method))
                        || (owner.equals("org.springframework.web.client.RestClient") && Set.of("create", "builder").contains(method))) {
                    events.add(SimpleConditionEvent.violated(clazz, clazz.getName() + " creates HTTP client"));
                }
            });
            clazz.getConstructorCallsFromSelf().forEach(call -> {
                String owner = call.getTarget().getOwner().getName();
                if (owner.equals("org.springframework.web.client.RestTemplate")
                        || owner.equals("java.net.http.HttpClient")) {
                    events.add(SimpleConditionEvent.violated(clazz, clazz.getName() + " constructs HTTP client"));
                }
            });
        }
    };

    private static String moduleOf(String packageName) {
        if (!packageName.startsWith(PREFIX)) return "";
        return packageName.substring(PREFIX.length()).split("\\.")[0];
    }

    private static ArchRule productionRule(ArchCondition<JavaClass> condition) {
        return classes().that().resideOutsideOfPackage("..archunit.violations..").should(condition);
    }

    @Test void productionArchitecture() {
        // Import all compiled server classes so future business packages are checked too.
        var production = new ClassFileImporter().importPath(java.nio.file.Path.of("target/classes"));
        for (var condition : Set.of(NO_CROSS_MODULE_MAPPER, NO_TOP_LEVEL_TECHNICAL_PACKAGE,
                INSIGHT_READ_ONLY, HTTP_THROUGH_INTEGRATION)) {
            productionRule(condition).check(production);
        }
    }

    @Test void eachRuleDetectsItsCounterexample() {
        assertViolation(NO_CROSS_MODULE_MAPPER, CrossModuleMapperViolation.class);
        assertViolation(NO_TOP_LEVEL_TECHNICAL_PACKAGE, TopLevelControllerViolation.class);
        assertViolation(INSIGHT_READ_ONLY, InsightWriteViolation.class);
        assertViolation(HTTP_THROUGH_INTEGRATION, DirectHttpClientViolation.class);
    }

    private static void assertViolation(ArchCondition<JavaClass> condition, Class<?> counterexample) {
        var imported = new ClassFileImporter().importClasses(counterexample);
        var result = classes().should(condition).evaluate(imported);
        assertTrue(result.hasViolation(), () -> "rule did not detect " + counterexample.getName());
    }
}
