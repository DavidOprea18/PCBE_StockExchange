package ro.upt.pcbe.bursa.verify;

import ro.upt.pcbe.bursa.engine.NamedThreadFactory;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically asks the JVM whether some threads are deadlocked
 * ({@link ThreadMXBean#findDeadlockedThreads()}, which also understands
 * {@code java.util.concurrent} locks, not only {@code synchronized}).
 *
 * <p>When a deadlock is found it builds a readable report (who holds which lock, who waits
 * for it) and opens a {@link CountDownLatch} so that waiting code can react.
 *
 * <p>Threads that were already deadlocked when the watchdog started (for example left over
 * by an earlier demo or test in the same JVM; deadlocked threads never go away) are ignored,
 * so each watchdog reports only the deadlocks of its own run.
 */
public final class DeadlockWatchdog implements AutoCloseable {

    private final ThreadMXBean mx = ManagementFactory.getThreadMXBean();
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("watchdog"));
    private final CountDownLatch detected = new CountDownLatch(1);
    /** Written once by the watchdog thread before countDown(); countDown/await give happens-before. */
    private volatile String report;
    /** Deadlocked before we started; written before the timer starts, read only by the timer thread. */
    private final Set<Long> preexisting = new HashSet<>();

    public DeadlockWatchdog start(long periodMillis) {
        long[] old = mx.findDeadlockedThreads();
        if (old != null) {
            for (long id : old) preexisting.add(id);
        }
        // Scheduling the task happens-before its execution, so the timer thread sees `preexisting`.
        timer.scheduleAtFixedRate(this::check, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        return this;
    }

    private void check() {
        if (detected.getCount() == 0) return;
        long[] found = mx.findDeadlockedThreads();
        if (found == null) return;
        long[] ids = Arrays.stream(found).filter(id -> !preexisting.contains(id)).toArray();
        if (ids.length == 0) return;
        StringBuilder sb = new StringBuilder("DEADLOCK between ").append(ids.length).append(" threads:\n");
        for (ThreadInfo info : mx.getThreadInfo(ids, true, true)) {
            if (info == null) continue;
            LockInfo waitingFor = info.getLockInfo();
            sb.append("  ").append(info.getThreadName())
              .append(" waits for ").append(waitingFor == null ? "?" : describe(waitingFor))
              .append(" held by ").append(info.getLockOwnerName()).append('\n');
            for (LockInfo held : info.getLockedSynchronizers()) {
                if (held.getClassName().contains("ThreadPoolExecutor$Worker")) continue; // pool internals, not ours
                sb.append("      and itself holds ").append(describe(held)).append('\n');
            }
        }
        report = sb.toString();
        detected.countDown();
    }

    private static String describe(LockInfo lock) {
        String cls = lock.getClassName();
        return cls.substring(cls.lastIndexOf('.') + 1) + "@" + Integer.toHexString(lock.getIdentityHashCode());
    }

    public boolean deadlockDetected() {
        return detected.getCount() == 0;
    }

    /** Waits up to the timeout for a deadlock to be detected. */
    public boolean awaitDeadlock(long timeout, TimeUnit unit) throws InterruptedException {
        return detected.await(timeout, unit);
    }

    public String report() {
        return report;
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
