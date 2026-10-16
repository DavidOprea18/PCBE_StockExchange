package ro.upt.pcbe.bursa;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ro.upt.pcbe.bursa.demo.CrossingTradesScenario;
import ro.upt.pcbe.bursa.engine.MarketConfig;
import ro.upt.pcbe.bursa.faulty.liveness.LivenessBug;
import ro.upt.pcbe.bursa.faulty.liveness.LivenessExchange;
import ro.upt.pcbe.bursa.faulty.races.RacyExchange;
import ro.upt.pcbe.bursa.sim.Simulation;
import ro.upt.pcbe.bursa.sim.SimulationConfig;
import ro.upt.pcbe.bursa.sim.SimulationResult;
import ro.upt.pcbe.bursa.sim.Variant;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that the faulty variants ARE faulty: the same workloads that the correct variant
 * passes (see CorrectConcurrencyTest) must expose problems here.
 *
 * <p>Tagged "faulty" and excluded from the default build, because race detection is
 * probabilistic and the deadlock test leaves two threads blocked forever (daemon threads,
 * so the JVM still exits). Run with: {@code mvn test -Pfaulty}.
 */
@Tag("faulty")
class FaultyVariantsTest {

    @Test
    @Timeout(120)
    void racesVariantBreaksInvariantsUnderTheSameWorkload() throws InterruptedException {
        List<String> evidence = new ArrayList<>();
        for (long seed = 1; seed <= 5 && evidence.isEmpty(); seed++) {   // retry: races are probabilistic
            SimulationResult r = Workloads.heavySimulation(Variant.RACES, 2_000, seed);
            evidence.addAll(r.violations());
            if (r.badSnapshots() > 0) evidence.add("torn snapshot: " + r.firstBadSnapshot());
            if (r.anomalies() > 0) evidence.add("exceptions: " + r.anomalySamples());
        }
        assertFalse(evidence.isEmpty(), "the racy exchange looked correct in 5 runs: lucky timing?");
        System.out.println("faulty-races evidence: " + evidence);
    }

    @Test
    @Timeout(60)
    void racesVariantLetsModificationsInterleaveWithTrades() throws InterruptedException {
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < 5 && problems.isEmpty(); i++) {
            try (RacyExchange ex = new RacyExchange(MarketConfig.standard(2))) {
                problems.addAll(Workloads.modifyWhileMatching(ex, 1_000));
            }
        }
        assertFalse(problems.isEmpty(), "expected R3 (modification during a trade) to corrupt state");
        System.out.println("modify-during-trade evidence: " + problems);
    }

    @Test
    @Timeout(60)
    void buyerThenSellerLockingDeadlocks() throws InterruptedException {
        LivenessExchange ex = new LivenessExchange(MarketConfig.standard(2), LivenessBug.DEADLOCK);
        CrossingTradesScenario.Result r = CrossingTradesScenario.run(ex, 20_000, 20_000);
        assertNotNull(r.deadlockReport(), "expected a deadlock, completed " + r.rounds() + " rounds");
        assertFalse(r.threadsStopped(), "deadlocked threads cannot be interrupted out of lock()");
        System.out.println(r.deadlockReport());
        // Not closing the exchange on purpose: its threads are stuck. They are daemon threads.
    }

    @Test
    @Timeout(60)
    void politeRetryLivelocks() throws InterruptedException {
        try (LivenessExchange ex = new LivenessExchange(MarketConfig.standard(2), LivenessBug.LIVELOCK)) {
            CrossingTradesScenario.run(ex, Long.MAX_VALUE, 2_000);
            assertTrue(ex.settlementRetries() > ex.settlements(),
                    "expected more failed attempts than settlements: " + ex.settlementRetries() + " vs " + ex.settlements());
        }
    }

    @Test
    @Timeout(60)
    void globalLockIsCorrectButMuchSlower() throws InterruptedException {
        SimulationResult global = new Simulation(
                SimulationConfig.defaults(Variant.LIVENESS).withBug(LivenessBug.GLOBAL_LOCK)
                        .withMarket(MarketConfig.standard(16)).withThink(0).withDuration(2_000).withoutObserver().quietly(),
                Workloads.SILENT).run();
        SimulationResult correct = new Simulation(
                SimulationConfig.defaults(Variant.CORRECT)
                        .withMarket(MarketConfig.standard(16)).withThink(0).withDuration(2_000).withoutObserver()
                        .withAudits(0).quietly(),
                Workloads.SILENT).run();
        assertTrue(global.violations().isEmpty(), "global lock is slow, not wrong: " + global.violations());
        assertTrue(correct.tradesPerSecond() > 3 * global.tradesPerSecond(),
                "expected per-stock locking to be much faster: " + correct.tradesPerSecond() + " vs " + global.tradesPerSecond());
    }
}
