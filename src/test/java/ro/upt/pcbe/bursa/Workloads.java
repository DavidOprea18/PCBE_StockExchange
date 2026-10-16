package ro.upt.pcbe.bursa;

import ro.upt.pcbe.bursa.api.ExchangeService;
import ro.upt.pcbe.bursa.api.OrderResult;
import ro.upt.pcbe.bursa.api.Side;
import ro.upt.pcbe.bursa.engine.MarketConfig;
import ro.upt.pcbe.bursa.sim.Simulation;
import ro.upt.pcbe.bursa.sim.SimulationConfig;
import ro.upt.pcbe.bursa.sim.SimulationResult;
import ro.upt.pcbe.bursa.sim.Variant;
import ro.upt.pcbe.bursa.verify.InvariantChecker;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Concurrent workloads shared by the tests. The SAME workloads run against the correct
 * variant (which must pass) and against the faulty ones (which must fail).
 */
final class Workloads {

    private Workloads() {
    }

    static final PrintStream SILENT = new PrintStream(OutputStream.nullOutputStream());

    /** All traders, no think time, snapshot observer on, audits while trading (correct only). */
    static SimulationResult heavySimulation(Variant variant, long millis, long seed) throws InterruptedException {
        SimulationConfig cfg = SimulationConfig.defaults(variant)
                .withMarket(MarketConfig.standard(16))
                .withThink(0)
                .withDuration(millis)
                .withSeed(seed)
                .withAudits(variant == Variant.CORRECT ? 200 : 0)
                .quietly();
        return new Simulation(cfg, SILENT).run();
    }

    /**
     * Targets the rule "an order cannot be modified while a trade on it is in progress".
     * One thread keeps changing the quantity of a resting SELL order, the other keeps buying
     * from that same order, one share at a time. Afterwards every invariant must still hold.
     *
     * @return the problems found (invariant violations and unexpected exceptions)
     */
    static List<String> modifyWhileMatching(ExchangeService ex, long millis) throws InterruptedException {
        String symbol = ex.symbols().get(0);
        long price = ex.config().listingPrice(symbol);
        int seller = 0;
        int buyer = 1;
        ConcurrentLinkedQueue<String> problems = new ConcurrentLinkedQueue<>();
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(2);

        // Only the modifier thread knows the sell order's id (thread confinement);
        // the buyer just sends buy orders that match whatever is resting.
        Thread modifier = new Thread(() -> {
            try {
                long orderId = -1;
                int q = 1;
                while (!stop.get()) {
                    if (orderId < 0 || ex.order(orderId).isEmpty()) {
                        OrderResult r = ex.placeOrder(seller, symbol, Side.SELL, price, 20);
                        orderId = r.accepted() && r.status().isActive() ? r.orderId() : -1;
                        if (!r.accepted()) break;                    // seller ran out of shares
                        continue;
                    }
                    q = q % 30 + 1;
                    ex.modifyOrder(seller, orderId, price, q);   // same price: quantity only
                }
            } catch (RuntimeException e) {
                problems.add("modifier: " + e);
            } finally {
                done.countDown();
            }
        }, "modifier");
        Thread buyerThread = new Thread(() -> {
            try {
                while (!stop.get()) {
                    OrderResult r = ex.placeOrder(buyer, symbol, Side.BUY, price, 1);
                    if (!r.accepted()) break;                        // buyer ran out of cash
                    if (r.status().isActive()) ex.cancelOrder(buyer, r.orderId()); // nothing to buy right now
                }
            } catch (RuntimeException e) {
                problems.add("buyer: " + e);
            } finally {
                done.countDown();
            }
        }, "buyer");
        modifier.setDaemon(true);
        buyerThread.setDaemon(true);
        modifier.start();
        buyerThread.start();
        Thread.sleep(millis);
        stop.set(true);
        if (!done.await(5, TimeUnit.SECONDS)) {
            problems.add("threads did not finish");
        }
        modifier.join(1_000);       // join: happens-before for the lock-free audit of faulty variants
        buyerThread.join(1_000);
        List<String> result = new ArrayList<>(problems);
        try {
            result.addAll(InvariantChecker.check(ex.config(), ex.audit()));
        } catch (RuntimeException e) {
            result.add("audit failed: " + e);
        }
        return result;
    }
}
