package com.example.actor.supervisor;

import com.example.actor.ActorRef;

public record Terminated(ActorRef<?> actor) {
}
