package ro.upt.pcbe.bursa;

import org.junit.jupiter.api.Test;
import ro.upt.pcbe.bursa.api.AccountView;
import ro.upt.pcbe.bursa.api.BookSnapshot;
import ro.upt.pcbe.bursa.api.OrderResult;
import ro.upt.pcbe.bursa.api.OrderStatus;
import ro.upt.pcbe.bursa.api.Side;
import ro.upt.pcbe.bursa.correct.CorrectExchange;
import ro.upt.pcbe.bursa.engine.MarketConfig;
import ro.upt.pcbe.bursa.verify.InvariantChecker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Functional behaviour of the correct exchange, single-threaded. */
class CorrectExchangeTest {

    /** 3 traders, 2 stocks, 1,000.00 cash and 100 shares of each per trader. */
    static MarketConfig small() {
        Map<String, Long> prices = new LinkedHashMap<>();
        prices.put("AAPL", 10_000L);
        prices.put("MSFT", 20_000L);
        return new MarketConfig(prices, 3, 100_000L, 100L, true);
    }

    private static void assertInvariants(CorrectExchange ex) {
        List<String> violations = InvariantChecker.check(ex.config(), ex.audit());
        assertTrue(violations.isEmpty(), "invariants: " + violations);
    }

    @Test
    void placingReservesAndCancellingReleases() {
        try (CorrectExchange ex = new CorrectExchange(small())) {
            OrderResult r = ex.placeOrder(0, "AAPL", Side.BUY, 9_000, 5);
            assertTrue(r.accepted());
            AccountView a = ex.account(0);
            assertEquals(100_000 - 45_000, a.cash());
            assertEquals(45_000, a.reservedCash());
            assertInvariants(ex);

            assertTrue(ex.cancelOrder(0, r.orderId()));
            assertFalse(ex.cancelOrder(0, r.orderId()), "second cancel fails");
            assertEquals(100_000, ex.account(0).cash());
            assertEquals(0, ex.account(0).reservedCash());
            assertTrue(ex.order(r.orderId()).isEmpty());
            assertInvariants(ex);
        }
    }

    @Test
    void cannotSpendMoneyOrSharesYouDoNotHave() {
        try (CorrectExchange ex = new CorrectExchange(small())) {
            assertFalse(ex.placeOrder(0, "AAPL", Side.BUY, 10_000, 11).accepted(), "needs 1,100.00 > 1,000.00");
            assertFalse(ex.placeOrder(0, "AAPL", Side.SELL, 10_000, 101).accepted(), "only 100 shares");
            assertTrue(ex.placeOrder(0, "AAPL", Side.SELL, 10_000, 100).accepted());
            assertFalse(ex.placeOrder(0, "AAPL", Side.SELL, 10_000, 1).accepted(), "all shares are in escrow");
            assertInvariants(ex);
        }
    }

    @Test
    void tradeSettlesAtRestingPriceAndRefundsTheBuyer() {
        try (CorrectExchange ex = new CorrectExchange(small())) {
            ex.placeOrder(1, "AAPL", Side.SELL, 10_000, 4);                 // seller asks 100.00
            OrderResult buy = ex.placeOrder(2, "AAPL", Side.BUY, 10_500, 4);  // buyer bids 105.00

            assertEquals(4, buy.filledNow());
            assertEquals(OrderStatus.FILLED, buy.status());
            AccountView buyer = ex.account(2);
            AccountView seller = ex.account(1);
            assertEquals(100_000 - 40_000, buyer.cash(), "paid 4 x 100.00, the 4 x 5.00 difference came back");
            assertEquals(0, buyer.reservedCash());
            assertEquals(104, buyer.totalShares("AAPL"));
            assertEquals(100_000 + 40_000, seller.cash());
            assertEquals(96, seller.totalShares("AAPL"));
            assertEquals(1, ex.tradeCount());
            assertEquals(10_000, ex.tradeHistory().get(0).price());
            assertInvariants(ex);
        }
    }

    @Test
    void modificationLosesPriorityAndCanTradeImmediately() {
        try (CorrectExchange ex = new CorrectExchange(small())) {
            OrderResult first = ex.placeOrder(0, "AAPL", Side.SELL, 10_000, 5);
            OrderResult second = ex.placeOrder(1, "AAPL", Side.SELL, 10_000, 5);
            // first re-submits at the same price: it goes behind second
            assertTrue(ex.modifyOrder(0, first.orderId(), 10_000, 6).accepted());
            ex.placeOrder(2, "AAPL", Side.BUY, 10_000, 5);
            assertTrue(ex.order(second.orderId()).isEmpty(), "second had priority and was filled");
            assertEquals(6, ex.order(first.orderId()).orElseThrow().remaining());

            // a bid at 99.00, then the seller lowers to 99.00: crosses, trades at the resting bid
            OrderResult bid = ex.placeOrder(1, "AAPL", Side.BUY, 9_900, 6);
            OrderResult mod = ex.modifyOrder(0, first.orderId(), 9_900, 6);
            assertEquals(6, mod.filledNow());
            assertTrue(ex.order(bid.orderId()).isEmpty());
            assertInvariants(ex);
        }
    }

    @Test
    void rejectedModificationChangesNothing() {
        try (CorrectExchange ex = new CorrectExchange(small())) {
            OrderResult r = ex.placeOrder(0, "AAPL", Side.BUY, 10_000, 5);
            assertFalse(ex.modifyOrder(0, r.orderId(), 10_000, 50).accepted(), "cannot afford 50");
            assertFalse(ex.modifyOrder(1, r.orderId(), 9_000, 1).accepted(), "not the owner");
            assertFalse(ex.cancelOrder(1, r.orderId()), "not the owner");
            var o = ex.order(r.orderId()).orElseThrow();
            assertEquals(10_000, o.price());
            assertEquals(5, o.remaining());
            assertInvariants(ex);
        }
    }

    @Test
    void snapshotShowsTopOfBookAndIsNeverCrossed() {
        try (CorrectExchange ex = new CorrectExchange(small())) {
            ex.placeOrder(0, "AAPL", Side.BUY, 9_900, 3);
            ex.placeOrder(1, "AAPL", Side.SELL, 10_100, 2);
            ex.placeOrder(2, "AAPL", Side.BUY, 10_100, 1);   // trades with the ask
            BookSnapshot s = ex.snapshot("AAPL");
            assertEquals(9_900, s.bestBid());
            assertEquals(10_100, s.bestAsk());
            assertEquals(1, s.volume());
            assertEquals(10_100, s.referencePrice());
            assertFalse(s.isCrossed());
        }
    }

    @Test
    void listenersReceiveEventsInOrderOnAnotherThread() {
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        List<String> threads = Collections.synchronizedList(new ArrayList<>());
        CorrectExchange ex = new CorrectExchange(small());
        ex.addListener(e -> {
            received.add(e.getClass().getSimpleName());
            threads.add(Thread.currentThread().getName());
        });
        ex.placeOrder(0, "AAPL", Side.SELL, 10_000, 1);
        ex.placeOrder(1, "AAPL", Side.BUY, 10_000, 1);
        ex.placeOrder(1, "AAPL", Side.BUY, 1, 1_000_000);   // rejected
        ex.close();                                          // drains the event queue

        assertEquals(List.of("OrderPlaced", "OrderPlaced", "TradeExecuted", "OrderRejected"), received);
        assertTrue(threads.stream().allMatch(n -> n.startsWith("events-")), "delivered on the dispatcher thread");
    }
}
