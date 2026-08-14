package no.beint.thim.benchmark;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

final class Measure {
    record Result(String name, long medianNanosPerOp, long p99NanosPerOp, int batchSize, int rounds) {
        @Override
        public String toString() {
            return "%s  median=%s/op  p99=%s/op  batch=%d  rounds=%d".formatted(
                    name,
                    formatNanos(medianNanosPerOp),
                    formatNanos(p99NanosPerOp),
                    batchSize,
                    rounds
            );
        }
    }

    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    private Measure() {}

    static Result run(String name, int warmupRounds, int measuredRounds, int batchSize, Action action) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(action);
        if (warmupRounds < 0 || measuredRounds < 1 || batchSize < 1) {
            throw new IllegalArgumentException("warmup, measured rounds, and batch must be positive");
        }
        try {
            for (var round = 0; round < warmupRounds; round++) {
                invoke(action, batchSize);
            }
            var samples = new long[measuredRounds];
            for (var round = 0; round < measuredRounds; round++) {
                var start = System.nanoTime();
                invoke(action, batchSize);
                samples[round] = System.nanoTime() - start;
            }
            Arrays.sort(samples);
            var result = new Result(
                    name,
                    samples[measuredRounds / 2] / batchSize,
                    samples[Math.min(measuredRounds - 1, (int) Math.ceil(measuredRounds * 0.99) - 1)] / batchSize,
                    batchSize,
                    measuredRounds
            );
            System.out.println("[thim-bench] " + result);
            return result;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(name + " failed", exception);
        }
    }

    static String formatNanos(long nanos) {
        if (nanos >= 1_000_000) {
            return String.format(Locale.ROOT, "%.2fms", nanos / 1_000_000.0);
        }
        if (nanos >= 1_000) {
            return String.format(Locale.ROOT, "%.1fµs", nanos / 1_000.0);
        }
        return nanos + "ns";
    }

    private static void invoke(Action action, int batchSize) throws Exception {
        for (var index = 0; index < batchSize; index++) {
            action.run();
        }
    }
}
