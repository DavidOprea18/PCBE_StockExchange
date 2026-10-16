package ro.upt.pcbe.bursa.demo;

import ro.upt.pcbe.bursa.api.ExchangeService;
import ro.upt.pcbe.bursa.api.OrderResult;
import ro.upt.pcbe.bursa.api.Side;
import ro.upt.pcbe.bursa.engine.NamedThreadFactory;
import ro.upt.pcbe.bursa.verify.DeadlockWatchdog;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The smallest situation that can deadlock a careless settlement: two traders, A and B,
 * trading two stocks in opposite directions at the same time.
 *
 * <pre>
 *   round:            even                          odd
 *   thread "cross-X": A sells X to B (buyer=B)      B sells X to A (buyer=A)
 *   thread "cross-Y": B sells Y to A (buyer=A)      A sells Y to B (buyer=B)
 * </pre>
 * The two settlements run under different stock locks (X and Y), so they really run in
 * parallel, and in every round they need the same two account locks with the roles swapped.
 * Locking "buyer then seller" makes one thread take B then A while the other takes A then B:
 * a cycle.
 *
 * <p>Each thread alternates the direction of its trades, so two consecutive rounds give every
 * share and every cent back: the scenario can run for as long as we like.
 */
public final class CrossingTradesScenario {

    public record Result(long roundsX, long roundsY, long elapsedMillis, String deadlockReport, boolean threadsStopped) {
        public long rounds() {
            return roundsX + roundsY;
        }
    }

    private CrossingTradesScenario() {
    }

    /**
     * Runs the scenario until {@code maxRounds} per thread, the time limit, or a deadlock.
     * The exchange must have at least 2 traders and 2 symbols.
     */
    public static Result run(ExchangeService exchange, long maxRounds, long timeLimitMillis) throws InterruptedException {
        List<String> symbols = exchange.symbols();
        String x = symbols.get(0);
        String y = symbols.get(1);
        int a = 0;
        int b = 1;
        long priceX = exchange.config().listingPrice(x);
        long priceY = exchange.config().listingPrice(y);

        AtomicLong roundsX = new AtomicLong();
        AtomicLong roundsY = new AtomicLong();
        CountDownLatch startGate = new CountDownLatch(1);   // both threads start together
        CountDownLatch finished = new CountDownLatch(2);

        Runnable crossX = loop(exchange, startGate, finished, roundsX, maxRounds, x, priceX, a, b);
        Runnable crossY = loop(exchange, startGate, finished, roundsY, maxRounds, y, priceY, b, a);
        NamedThreadFactory factory = new NamedThreadFactory("cross");
        Thread tx = factory.newThread(crossX);
        Thread ty = factory.newThread(crossY);

        try (DeadlockWatchdog watchdog = new DeadlockWatchdog().start(100)) {
            long start = System.nanoTime();
            tx.start();
            ty.start();
            startGate.countDown();

            long deadline = start + TimeUnit.MILLISECONDS.toNanos(timeLimitMillis);
            while (finished.getCount() > 0 && !watchdog.deadlockDetected() && System.nanoTime() < deadline) {
                finished.await(50, TimeUnit.MILLISECONDS);
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            tx.interrupt();
            ty.interrupt();
            boolean stopped = finished.await(1, TimeUnit.SECONDS);
            return new Result(roundsX.get(), roundsY.get(), elapsed, watchdog.report(), stopped);
        }
    }

    private static Runnable loop(ExchangeService exchange, CountDownLatch startGate, CountDownLatch finished,
                                 AtomicLong rounds, long maxRounds, String symbol, long price, int seller, int buyer) {
        return () -> {
            try {
                startGate.await();
                while (rounds.get() < maxRounds && !Thread.currentThread().isInterrupted()) {
                    boolean even = rounds.get() % 2 == 0;
                    int s = even ? seller : buyer;          // alternate direction every round
                    int b = even ? buyer : seller;
                    OrderResult sell = exchange.placeOrder(s, symbol, Side.SELL, price, 1);
                    OrderResult buy = exchange.placeOrder(b, symbol, Side.BUY, price, 1);
                    if (!sell.accepted() || !buy.accepted() || buy.filledNow() != 1) {
                        throw new IllegalStateException("scenario broken: " + sell + " / " + buy);
                    }
                    rounds.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        };
    }
}
