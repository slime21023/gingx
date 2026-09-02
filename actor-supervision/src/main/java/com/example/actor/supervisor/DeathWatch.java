package com.example.actor.supervisor;

import com.example.actor.ActorRef;
import com.example.actor.SendResult;
import com.example.actor.TerminationListener;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class DeathWatch {
    private final Map<Key, TerminationListener> registrations = new ConcurrentHashMap<>();

    public <M> void watch(ActorRef<Terminated> observer, ActorRef<?> target) {
        Objects.requireNonNull(observer, "observer");
        Objects.requireNonNull(target, "target");
        Key key = new Key(observer, target);
        TerminationListener listener = ignored -> {
            SendResult result = observer.send(new Terminated(target));
            if (result == SendResult.TERMINATED || result == SendResult.SYSTEM_CLOSED) {
                registrations.remove(key);
            }
        };
        TerminationListener previous = registrations.putIfAbsent(key, listener);
        if (previous == null) {
            target.addTerminationListener(listener);
        }
    }

    public void unwatch(ActorRef<?> observer, ActorRef<?> target) {
        Key key = new Key(observer, target);
        TerminationListener listener = registrations.remove(key);
        if (listener != null) {
            target.removeTerminationListener(listener);
        }
    }

    private record Key(ActorRef<?> observer, ActorRef<?> target) {
    }
}
