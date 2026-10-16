package ro.upt.pcbe.bursa.traders;

import ro.upt.pcbe.bursa.api.BookSnapshot;
import ro.upt.pcbe.bursa.api.ExchangeService;
import ro.upt.pcbe.bursa.api.OrderResult;
import ro.upt.pcbe.bursa.api.Side;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * A simulated buyer/seller. Each trader runs on its own pool thread and loops: look at the
 * shared information (snapshot, trade history), decide, act, think, repeat.
 *
 * <p>Thread-safety policy: <b>thread confinement</b>. Every field of a trader (its random
 * generator, its list of own orders) is used only by the thread running it, so it needs no
 * synchronization at all. All shared state lives in the exchange and in {@link TradingStats}.
 *
 * <p>Stopping: the loop checks the thread's interrupt flag. The simulation stops traders
 * with {@code ExecutorService.shutdownNow()}, which interrupts them. If the interrupt
 * arrives during the think-time sleep, {@code InterruptedException} is caught, the flag is
 * restored, and the loop ends; it is never swallowed.
 */
public abstract class Trader implements Runnable {

    protected static final int MAX_OPEN_ORDERS_PER_SYMBOL = 8;

    protected final int id;
    protected final ExchangeService exchange;
    protected final List<String> symbols;
    /** Seeded per trader: the decisions are reproducible, the thread interleaving is not. */
    protected final Random random;
    private final TradingStats stats;
    private final int maxThinkMicros;
    /** Ids of this trader's orders that may still be active, per symbol. Confined. */
    private final Map<String, List<Long>> myOrders = new HashMap<>();

    protected Trader(int id, ExchangeService exchange, long seed, TradingStats stats, int maxThinkMicros) {
        this.id = id;
        this.exchange = exchange;
        this.symbols = exchange.symbols();
        this.random = new Random(seed);
        this.stats = stats;
        this.maxThinkMicros = maxThinkMicros;
    }

    public int id() {
        return id;
    }

    public abstract String strategyName();

    /** One decision. Called repeatedly on the trader's own thread. */
    protected abstract void step();

    @Override
    public final void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                step();
                stats.operations.increment();
            } catch (RuntimeException e) {
                stats.anomaly(strategyName() + "#" + id, e);
            }
            if (!think()) {
                return;
            }
        }
    }

    /** Sleeps a random think time. Returns false if interrupted (time to stop). */
    private boolean think() {
        if (maxThinkMicros <= 0) {
            return true;
        }
        try {
            TimeUnit.MICROSECONDS.sleep(random.nextInt(maxThinkMicros + 1));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // restore the flag; do not swallow
            return false;
        }
    }

    // ---------------- helpers for strategies ----------------

    protected String randomSymbol() {
        return symbols.get(random.nextInt(symbols.size()));
    }

    /**
     * The price a trader considers fair: mostly the current mid price, pulled 10% towards the
     * listing price. The pull keeps the simulated market from drifting without bound.
     */
    protected long fairValue(String symbol, BookSnapshot book) {
        long listing = exchange.config().listingPrice(symbol);
        return Math.max(1L, Math.round(0.9 * book.midOrReference() + 0.1 * listing));
    }

    /** A price near {@code reference}: normal noise with the given spread in basis points. */
    protected long priceNear(long reference, double bps) {
        double factor = 1.0 + random.nextGaussian() * bps / 10_000.0;
        return Math.max(1L, Math.round(reference * factor));
    }

    protected List<Long> ordersOf(String symbol) {
        return myOrders.computeIfAbsent(symbol, s -> new ArrayList<>());
    }

    protected OrderResult place(String symbol, Side side, long price, int qty) {
        OrderResult r = exchange.placeOrder(id, symbol, side, price, qty);
        if (r.accepted()) {
            stats.placed.increment();
            if (r.status().isActive()) {
                ordersOf(symbol).add(r.orderId());
            }
        } else {
            stats.rejected.increment();
        }
        return r;
    }

    protected OrderResult modify(String symbol, long orderId, long price, int qty) {
        OrderResult r = exchange.modifyOrder(id, orderId, price, qty);
        if (r.accepted()) {
            stats.modified.increment();
            if (!r.status().isActive()) {
                ordersOf(symbol).remove(orderId);
            }
        } else {
            stats.rejected.increment();
            if (exchange.order(orderId).isEmpty()) {
                ordersOf(symbol).remove(orderId);   // it was filled or cancelled meanwhile
            }
        }
        return r;
    }

    protected boolean cancel(String symbol, long orderId) {
        ordersOf(symbol).remove(orderId);
        boolean ok = exchange.cancelOrder(id, orderId);
        if (ok) stats.cancelled.increment();
        return ok;
    }

    /** Cancels this trader's oldest order on {@code symbol}, if any. */
    protected void cancelOldest(String symbol) {
        List<Long> mine = ordersOf(symbol);
        if (!mine.isEmpty()) {
            cancel(symbol, mine.get(0));
        }
    }

    protected boolean tooManyOrders(String symbol) {
        return ordersOf(symbol).size() >= MAX_OPEN_ORDERS_PER_SYMBOL;
    }
}
