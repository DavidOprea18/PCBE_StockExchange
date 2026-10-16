package ro.upt.pcbe.bursa.sim;

import ro.upt.pcbe.bursa.api.BookSnapshot;
import ro.upt.pcbe.bursa.api.ExchangeService;
import ro.upt.pcbe.bursa.api.Trade;
import ro.upt.pcbe.bursa.engine.Money;
import ro.upt.pcbe.bursa.traders.TradingStats;

import java.io.PrintStream;

/**
 * Periodic console view of the market: best bid / best ask, last price and volume per stock,
 * plus global activity. Runs on the scheduler thread and reads only lock-free data
 * (snapshots and counters), so it never slows trading down in the correct variant.
 */
final class LiveView implements Runnable {

    private final ExchangeService exchange;
    private final TradingStats stats;
    private final PrintStream out;
    private final long startNanos;
    private long lastOps;          // confined to the scheduler thread
    private long lastNanos;

    LiveView(ExchangeService exchange, TradingStats stats, PrintStream out, long startNanos) {
        this.exchange = exchange;
        this.stats = stats;
        this.out = out;
        this.startNanos = startNanos;
        this.lastNanos = startNanos;
    }

    @Override
    public void run() {
        try {
            long now = System.nanoTime();
            long ops = stats.operations.sum();
            double rate = (ops - lastOps) * 1e9 / Math.max(1, now - lastNanos);
            lastOps = ops;
            lastNanos = now;
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("[%5.1fs] %,9d ops (%,7.0f/s) %,8d trades |", (now - startNanos) / 1e9, ops, rate,
                    exchange.tradeCount()));
            for (String s : exchange.symbols()) {
                BookSnapshot b = exchange.snapshot(s);
                Trade last = b.lastTrade();
                sb.append(String.format(" %s %s/%s last %s vol %,d |", s,
                        b.bestBid() == 0 ? "-" : Money.format(b.bestBid()),
                        b.bestAsk() == 0 ? "-" : Money.format(b.bestAsk()),
                        last == null ? "-" : Money.format(last.price()), b.volume()));
            }
            out.println(sb);
        } catch (RuntimeException e) {
            out.println("[live view] " + e);   // never let an exception cancel the periodic task
        }
    }
}
