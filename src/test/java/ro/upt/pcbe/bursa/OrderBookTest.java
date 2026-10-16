package ro.upt.pcbe.bursa;

import org.junit.jupiter.api.Test;
import ro.upt.pcbe.bursa.api.OrderStatus;
import ro.upt.pcbe.bursa.api.PriceLevel;
import ro.upt.pcbe.bursa.api.Side;
import ro.upt.pcbe.bursa.engine.Fill;
import ro.upt.pcbe.bursa.engine.Order;
import ro.upt.pcbe.bursa.engine.OrderBook;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic, single-threaded tests of the matching rules. No concurrency here: the
 * order book is plain business logic; locking is the engine's job.
 */
class OrderBookTest {

    private long seq;

    private Order order(long id, int trader, Side side, long price, int qty) {
        return new Order(id, trader, "AAPL", side, price, qty, seq++);
    }

    @Test
    void tradesAtTheRestingPriceAndLeavesTheRemainder() {
        OrderBook book = new OrderBook("AAPL");
        Order ask = order(1, 0, Side.SELL, 10_000, 30);
        book.add(ask);

        Order bid = order(2, 1, Side.BUY, 10_500, 50);      // willing to pay more than asked
        List<Fill> fills = book.match(bid);

        assertEquals(1, fills.size());
        assertEquals(10_000, fills.get(0).price(), "execution at the resting order's price");
        assertEquals(30, fills.get(0).quantity(), "n = min(offer, demand)");
        assertEquals(OrderStatus.FILLED, ask.status());
        assertEquals(OrderStatus.PARTIALLY_FILLED, bid.status());
        assertEquals(20, bid.remaining());
        assertEquals(0, book.size(), "filled resting order leaves the book; the incoming one is not added by match()");
    }

    @Test
    void betterPriceFirstThenOlderOrderFirst() {
        OrderBook book = new OrderBook("AAPL");
        Order oldAt101 = order(1, 0, Side.SELL, 10_100, 10);
        Order at100 = order(2, 1, Side.SELL, 10_000, 10);
        Order youngAt101 = order(3, 2, Side.SELL, 10_100, 10);
        book.add(oldAt101);
        book.add(at100);
        book.add(youngAt101);

        List<Fill> fills = book.match(order(4, 3, Side.BUY, 10_100, 25));

        assertEquals(3, fills.size());
        assertSame(at100, fills.get(0).sell(), "best (lowest) ask first");
        assertSame(oldAt101, fills.get(1).sell(), "same price: oldest first");
        assertSame(youngAt101, fills.get(2).sell());
        assertEquals(5, fills.get(2).quantity());
        assertEquals(5, youngAt101.remaining());
    }

    @Test
    void noTradeWhenPricesDoNotCross() {
        OrderBook book = new OrderBook("AAPL");
        book.add(order(1, 0, Side.SELL, 10_100, 10));
        Order bid = order(2, 1, Side.BUY, 10_000, 10);

        assertTrue(book.match(bid).isEmpty());
        book.add(bid);
        assertFalse(book.isCrossed());
        assertEquals(10_000, book.bestBid());
        assertEquals(10_100, book.bestAsk());
    }

    @Test
    void sellSideMatchesHighestBidsFirst() {
        OrderBook book = new OrderBook("AAPL");
        Order low = order(1, 0, Side.BUY, 9_900, 10);
        Order high = order(2, 1, Side.BUY, 10_000, 10);
        book.add(low);
        book.add(high);

        List<Fill> fills = book.match(order(3, 2, Side.SELL, 9_900, 15));

        assertEquals(2, fills.size());
        assertSame(high, fills.get(0).buy());
        assertEquals(10_000, fills.get(0).price());
        assertEquals(9_900, fills.get(1).price());
        assertEquals(5, low.remaining());
    }

    @Test
    void removeAndAggregatedLevels() {
        OrderBook book = new OrderBook("AAPL");
        Order a = order(1, 0, Side.BUY, 10_000, 10);
        Order b = order(2, 1, Side.BUY, 10_000, 15);
        Order c = order(3, 2, Side.BUY, 9_900, 5);
        book.add(a);
        book.add(b);
        book.add(c);

        List<PriceLevel> levels = book.levels(Side.BUY, 5);
        assertEquals(List.of(new PriceLevel(10_000, 25, 2), new PriceLevel(9_900, 5, 1)), levels);

        assertTrue(book.remove(b));
        assertFalse(book.remove(b), "removing twice is a no-op");
        assertTrue(book.remove(c));
        assertEquals(List.of(new PriceLevel(10_000, 10, 1)), book.levels(Side.BUY, 5));
        assertEquals(1, book.size());
    }
}
