package com.keel.starter.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Tool method marker. Invocation and approval are later tasks; this phase only records the method. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface KeelTool {
    String name() default "";
}
