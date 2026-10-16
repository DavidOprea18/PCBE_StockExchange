package ro.upt.pcbe.bursa.verify;

import ro.upt.pcbe.bursa.api.BookSnapshot;
import ro.upt.pcbe.bursa.api.ExchangeService;
import ro.upt.pcbe.bursa.api.PriceLevel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * A reader thread that hammers {@link ExchangeService#snapshot} while traders write, and
 * checks that every snapshot it sees is internally consistent:
 * <ul>
 *   <li>not crossed (a half-applied update can show the new bid with the old ask);</li>
 *   <li>bids sorted descending, asks ascending, positive quantities;</li>
 *   <li>versions of one stock never go backwards (no stale value after a newer one).</li>
 * </ul>
 * In the correct engine the count of bad snapshots is always 0 (immutable snapshot +
 * volatile publication). In faulty-races it is not.
 */
public final class SnapshotObserver implements Runnable {

    private final ExchangeService exchange;
    private final LongAdder reads = new LongAdder();
    private final LongAdder torn = new LongAdder();
    private final AtomicReference<String> firstProblem = new AtomicReference<>();

    public SnapshotObserver(ExchangeService exchange) {
        this.exchange = exchange;
    }

    @Override
    public void run() {
        List<String> symbols = exchange.symbols();
        Map<String, Long> lastVersion = new HashMap<>();   // confined to this thread
        while (!Thread.currentThread().isInterrupted()) {
            for (String s : symbols) {
                BookSnapshot snap;
                try {
                    snap = exchange.snapshot(s);
                } catch (RuntimeException e) {
                    report(s + ": snapshot() threw " + e);
                    continue;
                }
                reads.increment();
                String problem = problemWith(snap);
                long previous = lastVersion.getOrDefault(s, Long.MIN_VALUE);
                if (problem == null && snap.version() < previous) {
                    problem = "version went backwards " + previous + " -> " + snap.version();
                }
                lastVersion.put(s, Math.max(previous, snap.version()));
                if (problem != null) {
                    report(s + ": " + problem);
                }
            }
            Thread.onSpinWait();
        }
    }

    private void report(String problem) {
        torn.increment();
        firstProblem.compareAndSet(null, problem);
    }

    static String problemWith(BookSnapshot s) {
        if (s.isCrossed()) {
            return "crossed snapshot: bid " + s.bestBid() + " >= ask " + s.bestAsk() + " (version " + s.version() + ")";
        }
        List<PriceLevel> bids = s.bids();
        for (int i = 0; i < bids.size(); i++) {
            if (bids.get(i).quantity() <= 0) return "bid level with quantity " + bids.get(i).quantity();
            if (i > 0 && bids.get(i).price() >= bids.get(i - 1).price()) return "bids not sorted";
        }
        List<PriceLevel> asks = s.asks();
        for (int i = 0; i < asks.size(); i++) {
            if (asks.get(i).quantity() <= 0) return "ask level with quantity " + asks.get(i).quantity();
            if (i > 0 && asks.get(i).price() <= asks.get(i - 1).price()) return "asks not sorted";
        }
        return null;
    }

    public long reads() {
        return reads.sum();
    }

    public long inconsistentSnapshots() {
        return torn.sum();
    }

    public String firstProblem() {
        return firstProblem.get();
    }
}
