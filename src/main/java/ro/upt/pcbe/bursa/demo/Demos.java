package ro.upt.pcbe.bursa.demo;

import ro.upt.pcbe.bursa.api.ExchangeService;
import ro.upt.pcbe.bursa.correct.CorrectExchange;
import ro.upt.pcbe.bursa.engine.MarketConfig;
import ro.upt.pcbe.bursa.faulty.liveness.LivenessBug;
import ro.upt.pcbe.bursa.faulty.liveness.LivenessExchange;
import ro.upt.pcbe.bursa.sim.Report;
import ro.upt.pcbe.bursa.sim.Simulation;
import ro.upt.pcbe.bursa.sim.SimulationConfig;
import ro.upt.pcbe.bursa.sim.SimulationResult;
import ro.upt.pcbe.bursa.sim.Variant;
import ro.upt.pcbe.bursa.verify.InvariantChecker;

import java.io.PrintStream;
import java.util.List;

/**
 * Side-by-side demonstrations: each one runs the same workload on a faulty variant and on
 * the correct one, and prints the evidence.
 */
public final class Demos {

    private Demos() {
    }

    /** Faulty-races vs correct under heavy contention. */
    public static boolean races(PrintStream out, long millis) throws InterruptedException {
        out.println("""
                DEMO: data races (faulty-races) vs correct
                Workload: 16 traders, 4 stocks, no think time, so the same trader is often
                active on several stocks at once. A separate thread reads snapshots non-stop.
                Expected for faulty-races: money/shares not conserved, escrow mismatches,
                lost history entries, exceptions, crossed (torn) snapshots.
                """);
        SimulationConfig base = SimulationConfig.defaults(Variant.RACES)
                .withMarket(MarketConfig.standard(16)).withThink(0).withDuration(millis).quietly();
        SimulationResult racy = new Simulation(base, out).run();
        Report.print(racy, base.market(), out);

        SimulationConfig good = SimulationConfig.defaults(Variant.CORRECT)
                .withMarket(MarketConfig.standard(16)).withThink(0).withDuration(millis).quietly();
        SimulationResult correct = new Simulation(good, out).run();
        Report.print(correct, good.market(), out);
        return !racy.clean() && correct.clean();
    }

    /** Buyer-then-seller locking deadlocks; ordered locking does not. */
    public static boolean deadlock(PrintStream out) throws InterruptedException {
        out.println("""
                DEMO: deadlock (faulty-liveness, DEADLOCK) vs correct
                Two threads, two stocks, two traders trading in opposite directions:
                  thread X: A sells AAPL, B buys AAPL  -> settle(buyer=B, seller=A)
                  thread Y: B sells MSFT, A buys MSFT  -> settle(buyer=A, seller=B)
                faulty locks buyer first, then seller -> X holds B and wants A, Y holds A and wants B.
                """);
        MarketConfig market = MarketConfig.standard(2);
        try (ExchangeService faulty = new LivenessExchange(market, LivenessBug.DEADLOCK)) {
            CrossingTradesScenario.Result r = CrossingTradesScenario.run(faulty, 2_000, 10_000);
            out.printf("faulty-liveness: %d round trips in %d ms, then: %s%n", r.rounds(), r.elapsedMillis(),
                    r.deadlockReport() == null ? "no deadlock this time (try again)" : "");
            if (r.deadlockReport() != null) out.print(r.deadlockReport());
            out.println("  threads stopped after interrupt? " + r.threadsStopped()
                    + "  (lock() is not interruptible: deadlocked threads stay blocked forever)");
            boolean faultyDeadlocked = r.deadlockReport() != null;

            try (ExchangeService correct = new CorrectExchange(market)) {
                CrossingTradesScenario.Result c = CrossingTradesScenario.run(correct, 2_000, 10_000);
                List<String> v = InvariantChecker.check(market, correct.audit());
                out.printf("%ncorrect: %d round trips in %d ms, deadlock: %s, invariant violations: %d%n",
                        c.rounds(), c.elapsedMillis(), c.deadlockReport() == null ? "none" : "YES", v.size());
                return faultyDeadlocked && c.deadlockReport() == null && v.isEmpty() && c.rounds() == 4_000;
            }
        }
    }

    /** tryLock + immediate retry: threads stay busy but make little progress. */
    public static boolean livelock(PrintStream out, long millis) throws InterruptedException {
        out.println("""
                DEMO: livelock (faulty-liveness, LIVELOCK) vs correct
                Same two-thread crossing scenario. The faulty version avoids the deadlock with
                tryLock: if the second account is busy it releases the first and retries at once.
                Both threads keep backing off in lockstep: always busy, rarely successful.
                """);
        MarketConfig market = MarketConfig.standard(2);
        try (LivenessExchange faulty = new LivenessExchange(market, LivenessBug.LIVELOCK)) {
            CrossingTradesScenario.Result r = CrossingTradesScenario.run(faulty, Long.MAX_VALUE, millis);
            long settled = faulty.settlements();
            long retries = faulty.settlementRetries();
            out.printf("faulty-liveness: %,d settlements, %,d failed attempts (%.1f retries per settlement) in %d ms%n",
                    settled, retries, settled == 0 ? Double.POSITIVE_INFINITY : retries / (double) settled, r.elapsedMillis());
            try (ExchangeService correct = new CorrectExchange(market)) {
                CrossingTradesScenario.Result c = CrossingTradesScenario.run(correct, Long.MAX_VALUE, millis);
                out.printf("correct:         %,d settlements, 0 retries, in %d ms%n", correct.tradeCount(), c.elapsedMillis());
                out.println("""

                        Note: the faulty version also writes a 0.2 ms compliance log per attempt, so
                        compare it with the retry count, not only with correct's raw speed: every
                        failed attempt is wasted work that a correct lock order never has to do.""");
                return retries > settled;
            }
        }
    }
}
