package ro.upt.pcbe.bursa.traders;

import ro.upt.pcbe.bursa.api.BookSnapshot;
import ro.upt.pcbe.bursa.api.ExchangeService;
import ro.upt.pcbe.bursa.api.OrderResult;
import ro.upt.pcbe.bursa.api.Side;

import java.util.HashMap;
import java.util.Map;

/**
 * Market maker: keeps one bid and one ask on each stock, a small spread around the mid price,
 * and re-quotes them with {@code modifyOrder} as the market moves. It is the heaviest user
 * of modifications, which must never interleave with a trade on the same stock.
 *
 * <p>Inventory skew: if it holds more shares than it started with, it shifts both quotes
 * down (to sell more); if fewer, up. It learns its inventory from its own account, which it
 * re-reads only every few steps (reading takes the account lock).
 */
public final class MarketMakerTrader extends Trader {

    private static final double HALF_SPREAD = 0.0010;   // 10 bps each side
    private static final int REFRESH_EVERY = 25;

    private final Map<String, Long> bidIds = new HashMap<>();   // confined
    private final Map<String, Long> askIds = new HashMap<>();   // confined
    private final Map<String, Long> inventory = new HashMap<>(); // confined, refreshed periodically
    private int steps;

    public MarketMakerTrader(int id, ExchangeService exchange, long seed, TradingStats stats, int maxThinkMicros) {
        super(id, exchange, seed, stats, maxThinkMicros);
    }

    @Override
    public String strategyName() {
        return "MarketMaker";
    }

    @Override
    protected void step() {
        if (steps++ % REFRESH_EVERY == 0) {
            var account = exchange.account(id);
            for (String s : symbols) inventory.put(s, account.totalShares(s));
        }
        String symbol = randomSymbol();
        BookSnapshot book = exchange.snapshot(symbol);
        long mid = fairValue(symbol, book);
        long start = exchange.config().initialShares();
        double skew = (inventory.getOrDefault(symbol, start) - start) / (double) Math.max(1, start) * 0.002;
        long bid = Math.max(1, Math.round(mid * (1 - HALF_SPREAD - skew)));
        long ask = Math.max(bid + 1, Math.round(mid * (1 + HALF_SPREAD - skew)));
        int qty = 5 + random.nextInt(20);

        requote(symbol, Side.BUY, bidIds, bid, qty);
        requote(symbol, Side.SELL, askIds, ask, qty);
    }

    private void requote(String symbol, Side side, Map<String, Long> ids, long price, int qty) {
        Long existing = ids.get(symbol);
        if (existing != null) {
            OrderResult r = modify(symbol, existing, price, qty);
            if (r.accepted() && r.status().isActive()) {
                return;                      // quote updated in place
            }
            ids.remove(symbol);              // filled, cancelled, or could not afford it
            if (r.accepted()) return;        // it traded completely; quote again next time
        }
        OrderResult placed = place(symbol, side, price, qty);
        if (placed.accepted() && placed.status().isActive()) {
            ids.put(symbol, placed.orderId());
        }
    }
}
