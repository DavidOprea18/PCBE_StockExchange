package ro.upt.pcbe.bursa.traders;

import ro.upt.pcbe.bursa.api.BookSnapshot;
import ro.upt.pcbe.bursa.api.ExchangeService;
import ro.upt.pcbe.bursa.api.OrderView;
import ro.upt.pcbe.bursa.api.Side;

import java.util.List;
import java.util.Optional;

/**
 * Noise trader: places buy/sell orders at random prices around the current mid price,
 * and now and then modifies or cancels one of its orders.
 *
 * <p>Half of its modifications change only the quantity, half change the price. Together
 * with the market maker, this keeps modifications racing with trades on the same stock,
 * which is exactly the situation the "no modification during a trade" rule is about.
 */
public final class RandomTrader extends Trader {

    public RandomTrader(int id, ExchangeService exchange, long seed, TradingStats stats, int maxThinkMicros) {
        super(id, exchange, seed, stats, maxThinkMicros);
    }

    @Override
    public String strategyName() {
        return "Random";
    }

    @Override
    protected void step() {
        String symbol = randomSymbol();
        BookSnapshot book = exchange.snapshot(symbol);
        long reference = fairValue(symbol, book);
        List<Long> mine = ordersOf(symbol);
        int dice = random.nextInt(100);

        if (dice < 10 && !mine.isEmpty()) {
            cancel(symbol, mine.get(random.nextInt(mine.size())));
        } else if (dice < 30 && !mine.isEmpty()) {
            long orderId = mine.get(random.nextInt(mine.size()));
            Optional<OrderView> current = exchange.order(orderId);
            if (current.isEmpty()) {
                mine.remove(orderId);
                return;
            }
            OrderView o = current.get();
            if (random.nextBoolean()) {
                modify(symbol, orderId, o.price(), 1 + random.nextInt(30));          // quantity only
            } else {
                modify(symbol, orderId, priceNear(reference, 40), o.remaining());   // price only
            }
        } else if (tooManyOrders(symbol)) {
            cancelOldest(symbol);
        } else {
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            place(symbol, side, priceNear(reference, 40), 1 + random.nextInt(25));
        }
    }
}
