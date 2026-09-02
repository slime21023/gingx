package com.example.actor.supervisor;

import com.example.actor.Actor;
import com.example.actor.ActorOptions;

import java.util.Objects;
import java.util.function.Supplier;

public record ChildSpec<M>(String name, Supplier<? extends Actor<M>> factory, ActorOptions options) {
    public ChildSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(options, "options");
    }
}
