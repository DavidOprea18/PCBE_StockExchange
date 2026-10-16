package ro.upt.pcbe.bursa.traders;

import ro.upt.pcbe.bursa.api.BookSnapshot;
import ro.upt.pcbe.bursa.api.ExchangeService;
import ro.upt.pcbe.bursa.api.Side;
import ro.upt.pcbe.bursa.api.Trade;

import java.util.List;

/**
 * Trend follower: reads the shared trade history of a stock. If the last price is above the
 * average of the recent trades it buys aggressively (at the best ask, so it trades now);
 * if it is below, it sells aggressively at the best bid. Otherwise it rests a passive order.
 * Simple risk limits stop it from chasing a price far away from the listing price.
 */
public final class MomentumTrader extends Trader {

    private static final double THRESHOLD = 0.0005; // 5 basis points

    public MomentumTrader(int id, ExchangeService exchange, long seed, TradingStats stats, int maxThinkMicros) {
        super(id, exchange, seed, stats, maxThinkMicros);
    }

    @Override
    public String strategyName() {
        return "Momentum";
    }

    @Override
    protected void step() {
        String symbol = randomSymbol();
        if (tooManyOrders(symbol)) {
            cancelOldest(symbol);
            return;
        }
        BookSnapshot book = exchange.snapshot(symbol);
        List<Trade> trades = book.recentTrades();
        int qty = 1 + random.nextInt(10);
        if (trades.size() < 5) {
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            place(symbol, side, priceNear(book.midOrReference(), 30), qty);
            return;
        }
        double average = trades.stream().mapToLong(Trade::price).average().orElse(book.referencePrice());
        long last = trades.get(trades.size() - 1).price();
        // Risk limits: never chase a stock more than 20% away from its listing price.
        long listing = exchange.config().listingPrice(symbol);
        boolean mayBuy = last < listing * 1.2;
        boolean maySell = last > listing * 0.8;

        if (mayBuy && last > average * (1 + THRESHOLD)) {
            long price = book.bestAsk() != BookSnapshot.NO_PRICE ? book.bestAsk() : last;
            place(symbol, Side.BUY, price, qty);
        } else if (maySell && last < average * (1 - THRESHOLD)) {
            long price = book.bestBid() != BookSnapshot.NO_PRICE ? book.bestBid() : last;
            place(symbol, Side.SELL, price, qty);
        } else {
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            long offset = Math.max(1, Math.round(last * 0.001));
            place(symbol, side, side == Side.BUY ? last - offset : last + offset, qty);
        }
    }
}
