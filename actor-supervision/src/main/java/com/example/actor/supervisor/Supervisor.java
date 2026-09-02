package com.example.actor.supervisor;

import com.example.actor.Actor;
import com.example.actor.ActorOptions;
import com.example.actor.ActorRef;
import com.example.actor.ActorSystem;
import com.example.actor.FailureListener;
import com.example.actor.ManagedActorRef;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Erlang-style supervisor node with direct children and nested supervisor
 * subtrees. Restart windows are scoped to each node.
 */
public final class Supervisor implements AutoCloseable {
    private final ActorSystem system;
    private final RestartStrategy strategy;
    private final RestartWindow restartWindow;
    private final Supervisor parent;
    private final String name;
    private final List<ChildSlot<?>> children = new CopyOnWriteArrayList<>();
    private final Set<Supervisor> subtrees = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;

    public Supervisor(ActorSystem system, RestartStrategy strategy) {
        this(system, strategy, 5, Duration.ofSeconds(10));
    }

    public Supervisor(ActorSystem system, RestartStrategy strategy, int maxRestarts, Duration window) {
        this(system, strategy, maxRestarts, window, null, "root");
    }

    private Supervisor(ActorSystem system, RestartStrategy strategy, int maxRestarts,
                      Duration window, Supervisor parent, String name) {
        this.system = Objects.requireNonNull(system, "system");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.restartWindow = new RestartWindow(maxRestarts, window);
        this.parent = parent;
        this.name = Objects.requireNonNull(name, "name");
    }

    /** Creates a child supervisor whose crash-loop policy stops only its subtree. */
    public synchronized Supervisor spawnSupervisor(String childName, RestartStrategy childStrategy,
                                                    int maxRestarts, Duration window) {
        if (stopped) throw new IllegalStateException("Supervisor is stopped");
        Supervisor child = new Supervisor(system, childStrategy, maxRestarts, window, this, childName);
        subtrees.add(child);
        return child;
    }

    public Supervisor spawnSupervisor(String childName, RestartStrategy childStrategy) {
        return spawnSupervisor(childName, childStrategy, 5, Duration.ofSeconds(10));
    }

    public String name() {
        return name;
    }

    public Supervisor parent() {
        return parent;
    }

    public boolean isStopped() {
        return stopped;
    }

    public <M> ActorRef<M> spawn(ChildSpec<M> spec) {
        Objects.requireNonNull(spec, "spec");
        if (stopped) throw new IllegalStateException("Supervisor is stopped");
        ChildSlot<M> slot = new ChildSlot<>(spec, system.spawnManaged(spec.factory(), spec.options()));
        slot.ref.setFailureListener((actor, cause) -> onFailure(slot));
        children.add(slot);
        return slot.ref;
    }

    private void onFailure(ChildSlot<?> failed) {
        synchronized (this) {
            if (stopped) return;
            if (!restartWindow.allow(System.nanoTime())) {
                stopSubtree();
                return;
            }
            int index = children.indexOf(failed);
            if (index < 0) return;
            switch (strategy) {
                case ONE_FOR_ONE -> failed.ref.restart();
                case ONE_FOR_ALL -> children.forEach(child -> child.ref.restart());
                case REST_FOR_ONE -> {
                    for (int i = index; i < children.size(); i++) {
                        children.get(i).ref.restart();
                    }
                }
            }
        }
    }

    /** Stops this node and all descendants after the current in-flight message. */
    public synchronized void stopSubtree() {
        if (stopped) return;
        stopped = true;
        children.forEach(child -> child.ref.stop());
        subtrees.forEach(Supervisor::stopSubtree);
    }

    public int restartCountInWindow() {
        return restartWindow.size();
    }

    public List<ActorRef<?>> children() {
        return new ArrayList<>(children.stream().map(slot -> slot.ref).toList());
    }

    public List<Supervisor> subtrees() {
        return new ArrayList<>(subtrees);
    }

    @Override
    public void close() {
        stopSubtree();
    }

    private static final class ChildSlot<M> {
        private final ChildSpec<M> spec;
        private final ManagedActorRef<M> ref;

        private ChildSlot(ChildSpec<M> spec, ManagedActorRef<M> ref) {
            this.spec = spec;
            this.ref = ref;
        }
    }
}

