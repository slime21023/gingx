package com.example.actor;

public record ActorFailure(ActorRef<?> actor, Throwable cause) {
}
