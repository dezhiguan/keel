package com.keel.starter.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.stereotype.Component;

/** Marks a class whose {@link KeelEntry} is mounted as a Keel agent. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface KeelAgent {
    /** Manifest location. The default file is what turns auto-configuration on. */
    String manifest() default "classpath:agent.yaml";
}
