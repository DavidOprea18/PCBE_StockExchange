package ro.upt.pcbe.bursa.traders;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters shared by all traders of a simulation.
 *
 * <p>{@link LongAdder} instead of {@code AtomicLong}: many threads increment, one thread
 * (the live view) reads occasionally. LongAdder spreads increments over per-thread cells, so
 * writers do not fight over a single cache line; {@code sum()} is not an atomic snapshot,
 * which is fine for statistics.
 */
public final class TradingStats {

    public final LongAdder operations = new LongAdder();
    public final LongAdder placed = new LongAdder();
    public final LongAdder rejected = new LongAdder();
    public final LongAdder modified = new LongAdder();
    public final LongAdder cancelled = new LongAdder();
    public final LongAdder anomalies = new LongAdder();

    private static final int MAX_SAMPLES = 10;
    private final AtomicInteger samples = new AtomicInteger();
    private final ConcurrentLinkedQueue<String> anomalySamples = new ConcurrentLinkedQueue<>();

    /**
     * Records an unexpected exception thrown by the exchange. In the correct variant this must
     * never happen; in the faulty ones it is evidence of corrupted state.
     */
    public void anomaly(String who, RuntimeException e) {
        anomalies.increment();
        if (samples.getAndIncrement() < MAX_SAMPLES) {     // atomic "first N" without a lock
            anomalySamples.add(who + ": " + e);
        }
    }

    public List<String> anomalySamples() {
        return new ArrayList<>(anomalySamples);
    }
}
