package com.keel.starter.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** One entry method per {@link KeelAgent}. Parameters may be the request map and {@code KeelContext}. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface KeelEntry {
}
