package ro.upt.pcbe.bursa.engine;

import ro.upt.pcbe.bursa.api.ExchangeEvent;
import ro.upt.pcbe.bursa.api.ExchangeListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Delivers {@link ExchangeEvent}s to listeners asynchronously, on one dedicated thread.
 *
 * <p>Why a separate thread instead of calling listeners directly from the engine?
 * <ul>
 *   <li><b>No alien calls under a lock.</b> The engine publishes events while holding a stock
 *       lock (that is what keeps them in order). If it called listener code right there, a
 *       slow listener would stall trading, and a listener that calls back into the exchange
 *       on another stock could create a lock-ordering deadlock. Handing the event to a queue
 *       is fast and never calls foreign code.</li>
 *   <li><b>Ordering.</b> One consumer thread + a FIFO queue = listeners see events in exactly
 *       the order they were published. Since publication happens under the stock lock,
 *       events of one stock arrive in the order things happened to that stock.</li>
 * </ul>
 *
 * <p>Primitives: a single-thread {@link ExecutorService} (its internal
 * {@code LinkedBlockingQueue} is the event queue; the hand-off gives a happens-before edge
 * from publisher to listener) and a {@link CopyOnWriteArrayList} of listeners (registered
 * rarely, iterated on every event: the textbook case for copy-on-write).
 *
 * <p>The queue is unbounded on purpose: a bounded one would block the publisher, i.e. block
 * the engine while it holds a stock lock, whenever listeners fall behind. The trade-off is
 * memory growth if a listener is permanently too slow.
 */
public final class EventDispatcher implements AutoCloseable {

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(new NamedThreadFactory("events"));
    private final List<ExchangeListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong listenerErrors = new AtomicLong();

    public void addListener(ExchangeListener listener) {
        listeners.add(listener);
    }

    /** Enqueues an event. Fast, never blocks, never runs listener code on the caller's thread. */
    public void publish(ExchangeEvent event) {
        if (listeners.isEmpty()) {
            return; // nothing to deliver; avoids filling the queue in benchmarks
        }
        try {
            executor.execute(() -> deliver(event));
            published.incrementAndGet();
        } catch (RejectedExecutionException closed) {
            // The exchange is shutting down: late events are dropped, by design.
        }
    }

    private void deliver(ExchangeEvent event) {
        for (ExchangeListener l : listeners) {
            try {
                l.onEvent(event);
            } catch (RuntimeException e) {
                // A broken listener must not kill the dispatcher thread or starve the others.
                listenerErrors.incrementAndGet();
            }
        }
    }

    public long publishedCount() {
        return published.get();
    }

    public long listenerErrors() {
        return listenerErrors.get();
    }

    /** Delivers the events already queued (up to a timeout), then stops the thread. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt(); // keep the interrupt visible to our caller
        }
    }
}
