package com.annotation.concurrent;

public @interface GuardedBy {
    String[] value() default "unknown";
}
