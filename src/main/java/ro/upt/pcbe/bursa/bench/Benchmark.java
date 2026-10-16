package ro.upt.pcbe.bursa.bench;

import ro.upt.pcbe.bursa.engine.MarketConfig;
import ro.upt.pcbe.bursa.faulty.liveness.LivenessBug;
import ro.upt.pcbe.bursa.sim.Simulation;
import ro.upt.pcbe.bursa.sim.SimulationConfig;
import ro.upt.pcbe.bursa.sim.SimulationResult;
import ro.upt.pcbe.bursa.sim.Variant;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Throughput comparison of the same trader workload (no think time) on:
 * <ol>
 *   <li>correct, fair locks (default);</li>
 *   <li>correct, non-fair locks;</li>
 *   <li>faulty-liveness GLOBAL_LOCK: one lock for everything, slow log under it.</li>
 * </ol>
 * Not a rigorous micro-benchmark (no JMH): a short warm-up, then one timed run per variant.
 * Good enough to see differences of 2x or more; small differences are noise.
 */
public final class Benchmark {

    private Benchmark() {
    }

    public static List<SimulationResult> run(PrintStream out, int traders, long millis) throws InterruptedException {
        MarketConfig market = MarketConfig.standard(traders);
        out.printf("Benchmark: %d traders, %d stocks, %.1f s per variant, %d CPU cores%n",
                traders, market.symbols().size(), millis / 1000.0, Runtime.getRuntime().availableProcessors());

        // Warm-up so the JIT compiles the hot paths before we measure.
        new Simulation(base(Variant.CORRECT, market, 1_000), out).run();

        List<SimulationConfig> configs = List.of(
                base(Variant.CORRECT, market, millis),
                base(Variant.CORRECT, market.withFairLocks(false), millis),
                base(Variant.LIVENESS, market.withFairLocks(false), millis).withBug(LivenessBug.GLOBAL_LOCK));

        List<SimulationResult> results = new ArrayList<>();
        out.printf("%n%-36s %14s %14s %8s%n", "variant", "operations/s", "trades/s", "clean");
        for (SimulationConfig c : configs) {
            SimulationResult r = new Simulation(c, out).run();
            results.add(r);
            out.printf("%-36s %,14.0f %,14.0f %8s%n", r.variantName(), r.operationsPerSecond(), r.tradesPerSecond(),
                    r.clean() ? "yes" : "NO");
        }
        out.println("""

                How to read this:
                 - correct vs global lock: per-stock locks let different stocks trade in parallel and
                   keep readers lock-free; the global version serializes everything AND sleeps inside
                   the lock (compliance log), so all threads queue behind one slow writer.
                 - fair vs non-fair: fair locks hand the lock over in FIFO order (no starvation) at
                   the cost of more context switches; non-fair locks let a running thread barge in.
                 - with few cores, more threads than cores mostly adds contention; per-stock
                   parallelism shows best when cores >= stocks.""");
        return results;
    }

    private static SimulationConfig base(Variant v, MarketConfig market, long millis) {
        return SimulationConfig.defaults(v).withMarket(market).withThink(0).withDuration(millis).withAudits(0).withoutObserver().quietly();
    }
}
