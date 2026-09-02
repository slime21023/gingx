package com.example.actor.bench;

import com.example.actor.CancellationSource;
import com.example.actor.ReductionBudget;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(2)
public class PreemptionBenchmark {
    @State(Scope.Thread)
    public static class LoopState {
        ReductionBudget budget;
        int seed;

        @Setup(Level.Iteration)
        public void setup() {
            budget = new ReductionBudget(4096, new CancellationSource().token(), () -> {});
            seed = 17;
        }
    }

    @Benchmark
    public int plainLoop(LoopState state) {
        int value = state.seed;
        for (int i = 0; i < 4096; i++) value += i;
        state.seed = value;
        return value;
    }

    @Benchmark
    public int reductionLoop(LoopState state) {
        int value = state.seed;
        for (int i = 0; i < 4096; i++) {
            state.budget.tick();
            value += i;
        }
        state.seed = value;
        return value;
    }
}
