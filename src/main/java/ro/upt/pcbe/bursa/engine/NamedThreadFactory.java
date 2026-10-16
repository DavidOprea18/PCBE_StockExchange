package ro.upt.pcbe.bursa.engine;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates named daemon threads. Names make thread dumps and deadlock reports readable.
 * Daemon threads guarantee that a demo whose threads are deadlocked (on purpose) still lets
 * the JVM exit.
 */
public final class NamedThreadFactory implements ThreadFactory {

    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger();

    public NamedThreadFactory(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
        t.setDaemon(true);
        return t;
    }
}
