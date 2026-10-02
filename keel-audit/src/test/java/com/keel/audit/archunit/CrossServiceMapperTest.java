package com.keel.audit.archunit;

import com.keel.audit.archunit.violations.CrossServiceMapperViolation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossServiceMapperTest {
    private static final ArchCondition<JavaClass> NO_SERVER_MAPPER = new ArchCondition<>("not depend on keel-server mappers") {
        @Override public void check(JavaClass origin, ConditionEvents events) {
            origin.getDirectDependenciesFromSelf().forEach(dependency -> {
                String targetPackage = dependency.getTargetClass().getPackageName();
                if (targetPackage.startsWith("com.keel.server.")
                        && (targetPackage.contains(".mapper.") || targetPackage.endsWith(".mapper"))) {
                    events.add(SimpleConditionEvent.violated(origin,
                            origin.getName() + " depends on " + dependency.getTargetClass().getName()));
                }
            });
        }
    };

    private static ArchRule productionRule() {
        return classes().that().resideOutsideOfPackage("..archunit.violations..").should(NO_SERVER_MAPPER);
    }

    @Test void auditMustNotUseServerMappers() {
        productionRule().check(new ClassFileImporter().importPath(Path.of("target/classes")));
    }

    @Test void ruleDetectsCrossServiceMapper() {
        var result = classes().should(NO_SERVER_MAPPER)
                .evaluate(new ClassFileImporter().importClasses(CrossServiceMapperViolation.class));
        assertTrue(result.hasViolation(), "rule must reject a keel-server mapper dependency");
    }
}
