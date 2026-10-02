package com.keel.server.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a config-changing endpoint; an aspect writes a config.change audit event for it.
 * TODO(P1-4): add the aspect; until then this annotation has no effect.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {}
