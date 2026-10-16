package ro.upt.pcbe.bursa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ro.upt.pcbe.bursa.correct.CorrectExchange;
import ro.upt.pcbe.bursa.demo.CrossingTradesScenario;
import ro.upt.pcbe.bursa.engine.MarketConfig;
import ro.upt.pcbe.bursa.sim.Simulation;
import ro.upt.pcbe.bursa.sim.SimulationConfig;
import ro.upt.pcbe.bursa.sim.SimulationResult;
import ro.upt.pcbe.bursa.sim.Variant;
import ro.upt.pcbe.bursa.verify.InvariantChecker;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stress tests for the correct variant. Concurrency bugs are probabilistic, so each test
 * runs a heavy workload, several times, and checks every invariant (also while trading).
 * A pass is evidence, not proof; the proof is the reasoning in DESIGN.md.
 */
class CorrectConcurrencyTest {

    @Test
    @Timeout(120)
    void heavyTradingKeepsEveryInvariant() throws InterruptedException {
        for (long seed = 1; seed <= 3; seed++) {
            SimulationResult r = Workloads.heavySimulation(Variant.CORRECT, 2_000, seed);
            assertTrue(r.trades() > 1_000, "the workload must really trade, got " + r.trades());
            assertTrue(r.periodicAudits() > 0, "audits must have run while trading");
            assertEquals(0, r.anomalies(), "unexpected exceptions: " + r.anomalySamples());
            assertEquals(0, r.badSnapshots(), "torn snapshot: " + r.firstBadSnapshot());
            assertTrue(r.periodicViolations().isEmpty(), "violations while trading: " + r.periodicViolations());
            assertTrue(r.violations().isEmpty(), "final violations: " + r.violations());
            assertNull(r.deadlockReport());
            assertTrue(r.clean());
        }
    }

    @Test
    @Timeout(60)
    void crossedRolesNeverDeadlock() throws InterruptedException {
        MarketConfig market = MarketConfig.standard(2);
        try (CorrectExchange ex = new CorrectExchange(market)) {
            CrossingTradesScenario.Result r = CrossingTradesScenario.run(ex, 20_000, 30_000);
            assertNull(r.deadlockReport());
            assertEquals(40_000, r.rounds(), "both threads completed every round");
            assertTrue(InvariantChecker.check(market, ex.audit()).isEmpty());
        }
    }

    @Test
    @Timeout(60)
    void modificationNeverInterleavesWithATrade() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            try (CorrectExchange ex = new CorrectExchange(MarketConfig.standard(2))) {
                List<String> problems = Workloads.modifyWhileMatching(ex, 1_000);
                assertTrue(problems.isEmpty(), "problems: " + problems);
                assertTrue(ex.tradeCount() > 100, "must actually trade, got " + ex.tradeCount());
            }
        }
    }

    @Test
    @Timeout(60)
    void nonFairLocksAreAlsoCorrect() throws InterruptedException {
        SimulationResult r = new Simulation(
                SimulationConfig.defaults(Variant.CORRECT)
                        .withMarket(MarketConfig.standard(12).withFairLocks(false))
                        .withThink(0).withDuration(1_500).withAudits(200).quietly(),
                Workloads.SILENT).run();
        assertTrue(r.clean(), "violations: " + r.violations() + " " + r.periodicViolations());
    }
}
