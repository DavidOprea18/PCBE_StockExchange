package ro.upt.pcbe.bursa.sim;

import ro.upt.pcbe.bursa.api.AuditSnapshot;

import java.util.List;
import java.util.Map;

/**
 * Everything measured by one simulation run.
 *
 * @param variantName        exchange variant
 * @param elapsedMillis      actual trading time
 * @param traders            number of traders
 * @param operations         trader actions completed
 * @param placed             accepted placements
 * @param rejected           rejected placements/modifications
 * @param modified           accepted modifications
 * @param cancelled          successful cancellations
 * @param trades             trades executed (engine counter)
 * @param anomalies          unexpected exceptions thrown to traders
 * @param anomalySamples     first few of them
 * @param snapshotReads      snapshots read by the observer thread
 * @param badSnapshots       inconsistent snapshots seen by the observer
 * @param firstBadSnapshot   description of the first one
 * @param deadlockReport     non-null if the watchdog found a deadlock
 * @param stuckTraders       true if trader threads did not stop when asked
 * @param violations         invariant violations found by the final audit
 * @param auditError         non-null if the final audit could not run
 * @param periodicAudits     audits performed while trading
 * @param periodicViolations violations found by those audits
 * @param events             events received by the subscriber, by type
 * @param settlementRetries  liveness variant only: failed lock attempts (else -1)
 * @param finalAudit         the final audit (null if it could not run)
 */
public record SimulationResult(String variantName, long elapsedMillis, int traders,
                               long operations, long placed, long rejected, long modified, long cancelled,
                               long trades, long anomalies, List<String> anomalySamples,
                               long snapshotReads, long badSnapshots, String firstBadSnapshot,
                               String deadlockReport, boolean stuckTraders,
                               List<String> violations, String auditError,
                               long periodicAudits, List<String> periodicViolations,
                               Map<String, Long> events, long settlementRetries,
                               AuditSnapshot finalAudit) {

    /** True if nothing at all went wrong: what the correct variant must always achieve. */
    public boolean clean() {
        return anomalies == 0 && badSnapshots == 0 && deadlockReport == null && !stuckTraders
                && violations.isEmpty() && auditError == null && periodicViolations.isEmpty() && !livelockSymptom();
    }

    /** More failed lock attempts than completed trades: threads are busy but not progressing. */
    public boolean livelockSymptom() {
        return settlementRetries > trades;
    }

    public double operationsPerSecond() {
        return elapsedMillis == 0 ? 0 : operations * 1000.0 / elapsedMillis;
    }

    public double tradesPerSecond() {
        return elapsedMillis == 0 ? 0 : trades * 1000.0 / elapsedMillis;
    }
}
