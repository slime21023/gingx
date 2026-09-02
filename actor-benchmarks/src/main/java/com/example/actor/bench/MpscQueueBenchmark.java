package com.example.actor.bench;

import com.example.queue.MpscBoundedArrayQueue;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(2)
public class MpscQueueBenchmark {
    /** The maximum mailbox capacity the runtime supports. */
    private static final int MAILBOX_CAPACITY = 65_536;

    @State(Scope.Group)
    public static class QueueState {
        MpscBoundedArrayQueue<Object> queue;
        Object message;

        @Setup(Level.Iteration)
        public void setup() {
            queue = new MpscBoundedArrayQueue<>(MAILBOX_CAPACITY);
            message = new Object();
        }
    }

    @Benchmark
    @Group("mpsc")
    @GroupThreads(3)
    public boolean offer(QueueState state) {
        return state.queue.offer(state.message);
    }

    @Benchmark
    @Group("mpsc")
    @GroupThreads(1)
    public Object poll(QueueState state) {
        return state.queue.poll();
    }
}
