package ro.upt.pcbe.bursa.sim;

import ro.upt.pcbe.bursa.engine.MarketConfig;
import ro.upt.pcbe.bursa.faulty.liveness.LivenessBug;

/**
 * Everything needed to run one simulation. Immutable.
 *
 * @param variant           which exchange implementation to run
 * @param bug               which bug, for the liveness variant (ignored otherwise)
 * @param market            symbols, traders, endowments, lock fairness
 * @param durationMillis    how long traders trade
 * @param seed              base random seed (trader i uses seed + i)
 * @param maxThinkMicros    each trader sleeps 0..maxThinkMicros between actions (0 = never)
 * @param liveViewMillis    period of the console market view (0 = off)
 * @param auditEveryMillis  period of invariant checks WHILE trading (0 = off; correct variant only)
 * @param quiet             if true, print nothing while running
 * @param observer          run the snapshot observer thread (it spins on one core; off in benchmarks)
 */
public record SimulationConfig(Variant variant, LivenessBug bug, MarketConfig market, long durationMillis,
                               long seed, int maxThinkMicros, long liveViewMillis, long auditEveryMillis,
                               boolean quiet, boolean observer) {

    public static SimulationConfig defaults(Variant variant) {
        return new SimulationConfig(variant, LivenessBug.DEADLOCK, MarketConfig.standard(12), 10_000, 42,
                200, 1_000, variant == Variant.CORRECT ? 500 : 0, false, true);
    }

    public SimulationConfig withDuration(long millis) {
        return new SimulationConfig(variant, bug, market, millis, seed, maxThinkMicros, liveViewMillis, auditEveryMillis, quiet, observer);
    }

    public SimulationConfig withMarket(MarketConfig m) {
        return new SimulationConfig(variant, bug, m, durationMillis, seed, maxThinkMicros, liveViewMillis, auditEveryMillis, quiet, observer);
    }

    public SimulationConfig withBug(LivenessBug b) {
        return new SimulationConfig(variant, b, market, durationMillis, seed, maxThinkMicros, liveViewMillis, auditEveryMillis, quiet, observer);
    }

    public SimulationConfig withSeed(long s) {
        return new SimulationConfig(variant, bug, market, durationMillis, s, maxThinkMicros, liveViewMillis, auditEveryMillis, quiet, observer);
    }

    public SimulationConfig withThink(int micros) {
        return new SimulationConfig(variant, bug, market, durationMillis, seed, micros, liveViewMillis, auditEveryMillis, quiet, observer);
    }

    public SimulationConfig withLiveView(long millis) {
        return new SimulationConfig(variant, bug, market, durationMillis, seed, maxThinkMicros, millis, auditEveryMillis, quiet, observer);
    }

    public SimulationConfig withAudits(long millis) {
        return new SimulationConfig(variant, bug, market, durationMillis, seed, maxThinkMicros, liveViewMillis, millis, quiet, observer);
    }

    public SimulationConfig withoutObserver() {
        return new SimulationConfig(variant, bug, market, durationMillis, seed, maxThinkMicros, liveViewMillis, auditEveryMillis, quiet, false);
    }

    public SimulationConfig quietly() {
        return new SimulationConfig(variant, bug, market, durationMillis, seed, maxThinkMicros, 0, auditEveryMillis, true, observer);
    }
}
