package com.example.actor.groovy;

import org.codehaus.groovy.transform.GroovyASTTransformationClass;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD})
@GroovyASTTransformationClass("com.example.actor.groovy.PreemptiveASTTransformation")
public @interface Preemptive {
    int budget() default 4096;
}
