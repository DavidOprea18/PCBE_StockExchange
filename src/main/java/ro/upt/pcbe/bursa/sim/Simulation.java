package ro.upt.pcbe.bursa.sim;

import ro.upt.pcbe.bursa.api.AuditSnapshot;
import ro.upt.pcbe.bursa.api.ExchangeEvent;
import ro.upt.pcbe.bursa.api.ExchangeService;
import ro.upt.pcbe.bursa.engine.NamedThreadFactory;
import ro.upt.pcbe.bursa.faulty.liveness.LivenessExchange;
import ro.upt.pcbe.bursa.traders.MarketMakerTrader;
import ro.upt.pcbe.bursa.traders.MomentumTrader;
import ro.upt.pcbe.bursa.traders.RandomTrader;
import ro.upt.pcbe.bursa.traders.Trader;
import ro.upt.pcbe.bursa.traders.TradingStats;
import ro.upt.pcbe.bursa.verify.DeadlockWatchdog;
import ro.upt.pcbe.bursa.verify.InvariantChecker;
import ro.upt.pcbe.bursa.verify.SnapshotObserver;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs one simulation: creates the exchange and the traders, lets them trade for the
 * configured time while observers watch, stops everything cleanly, and checks invariants.
 *
 * <p>Threads involved:
 * <ul>
 *   <li>N trader threads (fixed thread pool, one long-running task per trader);</li>
 *   <li>1 snapshot observer (reads market data as fast as it can, looking for torn reads);</li>
 *   <li>1 scheduler for the live view and periodic audits;</li>
 *   <li>1 deadlock watchdog;</li>
 *   <li>1 event dispatcher (inside the exchange);</li>
 *   <li>the main thread, which waits and then coordinates shutdown.</li>
 * </ul>
 */
public final class Simulation {

    private final SimulationConfig config;
    private final PrintStream out;

    public Simulation(SimulationConfig config, PrintStream out) {
        this.config = config;
        this.out = out;
    }

    public static List<Trader> createTraders(ExchangeService exchange, SimulationConfig config, TradingStats stats) {
        List<Trader> traders = new ArrayList<>();
        for (int i = 0; i < config.market().traders(); i++) {
            long seed = config.seed() + i;
            int think = config.maxThinkMicros();
            traders.add(switch (i % 4) {
                case 0 -> new MarketMakerTrader(i, exchange, seed, stats, think);
                case 1 -> new MomentumTrader(i, exchange, seed, stats, think);
                default -> new RandomTrader(i, exchange, seed, stats, think);
            });
        }
        return traders;
    }

    public SimulationResult run() throws InterruptedException {
        ExchangeService exchange = config.variant().create(config.market(), config.bug());
        EventCounter eventCounter = new EventCounter();
        exchange.addListener(eventCounter);
        TradingStats stats = new TradingStats();
        List<Trader> traders = createTraders(exchange, config, stats);

        SnapshotObserver observer = new SnapshotObserver(exchange);
        Thread observerThread = new NamedThreadFactory("observer").newThread(observer);
        DeadlockWatchdog watchdog = new DeadlockWatchdog().start(200);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("scheduler"));
        ExecutorService pool = Executors.newFixedThreadPool(traders.size(), new NamedThreadFactory("trader"));

        ConcurrentLinkedQueue<String> periodicViolations = new ConcurrentLinkedQueue<>();
        AtomicLong periodicAudits = new AtomicLong();
        long start = System.nanoTime();
        try {
            if (!config.quiet()) {
                out.printf("Running %s with %d traders on %s for %.1f s (seed %d)%n", exchange.variantName(),
                        traders.size(), exchange.symbols(), config.durationMillis() / 1000.0, config.seed());
            }
            if (config.liveViewMillis() > 0) {
                LiveView view = new LiveView(exchange, stats, out, start);
                scheduler.scheduleAtFixedRate(view, config.liveViewMillis(), config.liveViewMillis(), TimeUnit.MILLISECONDS);
            }
            if (config.auditEveryMillis() > 0) {
                // Invariants checked WHILE traders run: only possible because the correct audit
                // takes every lock in the global order and therefore sees a consistent cut.
                scheduler.scheduleAtFixedRate(() -> {
                    try {
                        List<String> v = InvariantChecker.check(config.market(), exchange.audit());
                        periodicAudits.incrementAndGet();
                        periodicViolations.addAll(v);
                    } catch (RuntimeException e) {
                        // an exception would silently cancel a periodic task; record it instead
                        periodicViolations.add("periodic audit failed: " + e);
                    }
                },config.auditEveryMillis(), config.auditEveryMillis(), TimeUnit.MILLISECONDS);
            }

            if (config.observer()) {
                observerThread.start();
            }
            List<Future<?>> futures = new ArrayList<>();
            for (Trader t : traders) {
                futures.add(pool.submit(t));
            }

            // Wait for the duration, but wake up early if the watchdog sees a deadlock.
            watchdog.awaitDeadlock(config.durationMillis(), TimeUnit.MILLISECONDS);

            // ---- clean shutdown ----
            pool.shutdownNow();                                   // interrupts every trader
            boolean stopped = pool.awaitTermination(3, TimeUnit.SECONDS);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            for (Future<?> f : futures) {
                // Future.get() of a finished task gives a happens-before edge from everything the
                // trader did to this thread: needed by the lock-free audits of the faulty variants.
                try {
                    f.get(10, TimeUnit.MILLISECONDS);
                } catch (ExecutionException | CancellationException | TimeoutException ignored) {
                    // a stuck trader (deadlock/livelock) is reported below
                }
            }
            observerThread.interrupt();
            observerThread.join(1_000);
            scheduler.shutdownNow();
            scheduler.awaitTermination(2, TimeUnit.SECONDS);

            List<String> violations = new ArrayList<>();
            String auditError = null;
            AuditSnapshot audit = null;
            if (!stopped) {
                auditError = "trader threads did not stop: they are blocked or spinning";
            } else {
                try {
                    audit = exchange.audit();
                    violations.addAll(InvariantChecker.check(config.market(), audit));
                } catch (RuntimeException e) {
                    auditError = "audit failed: " + e;
                }
            }
            exchange.close();   // drains the event queue, so the counts below are final
            long trades = exchange.tradeCount();
            long tradeEvents = eventCounter.count(ExchangeEvent.TradeExecuted.class);
            if (stopped && tradeEvents != trades) {
                violations.add("subscriber received " + tradeEvents + " TradeExecuted events for " + trades + " trades");
            }

            long retries = exchange instanceof LivenessExchange le ? le.settlementRetries() : -1;
            return new SimulationResult(exchange.variantName(), elapsedMillis, traders.size(),
                    stats.operations.sum(), stats.placed.sum(), stats.rejected.sum(), stats.modified.sum(),
                    stats.cancelled.sum(), trades, stats.anomalies.sum(), stats.anomalySamples(),
                    observer.reads(), observer.inconsistentSnapshots(), observer.firstProblem(),
                    watchdog.report(), !stopped, violations, auditError,
                    periodicAudits.get(), new ArrayList<>(periodicViolations),
                    eventCounter.snapshot(), retries, audit);
        } finally {
            pool.shutdownNow();
            scheduler.shutdownNow();
            observerThread.interrupt();
            watchdog.close();
        }
    }
}
