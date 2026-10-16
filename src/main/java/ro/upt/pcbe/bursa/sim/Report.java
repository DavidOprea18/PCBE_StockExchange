package ro.upt.pcbe.bursa.sim;

import ro.upt.pcbe.bursa.api.AccountView;
import ro.upt.pcbe.bursa.api.AuditSnapshot;
import ro.upt.pcbe.bursa.api.BookSnapshot;
import ro.upt.pcbe.bursa.engine.MarketConfig;
import ro.upt.pcbe.bursa.engine.Money;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Prints the final report of a simulation. */
public final class Report {

    private Report() {
    }

    public static String strategyOf(int traderId) {
        return switch (traderId % 4) {
            case 0 -> "MarketMaker";
            case 1 -> "Momentum";
            default -> "Random";
        };
    }

    public static void print(SimulationResult r, MarketConfig market, PrintStream out) {
        out.println();
        out.println("=================== FINAL REPORT: " + r.variantName() + " ===================");
        out.printf("Trading time        %.2f s, %d traders%n", r.elapsedMillis() / 1000.0, r.traders());
        out.printf("Operations          %,d  (%,.0f/s)%n", r.operations(), r.operationsPerSecond());
        out.printf("  placed %,d | modified %,d | cancelled %,d | rejected %,d%n",
                r.placed(), r.modified(), r.cancelled(), r.rejected());
        out.printf("Trades executed     %,d  (%,.0f/s)%n", r.trades(), r.tradesPerSecond());
        out.println("Events received     " + r.events());
        if (r.settlementRetries() >= 0) {
            out.printf("Settlement retries  %,d%n", r.settlementRetries());
        }

        AuditSnapshot audit = r.finalAudit();
        if (audit != null) {
            printMarkets(audit, out);
            printProfitAndLoss(audit, market, out);
        }

        out.println("---- concurrency checks ----");
        out.printf("Unexpected exceptions      %,d%n", r.anomalies());
        for (String s : r.anomalySamples()) out.println("    " + s);
        out.printf("Inconsistent snapshots     %,d of %,d reads%n", r.badSnapshots(), r.snapshotReads());
        if (r.firstBadSnapshot() != null) out.println("    first: " + r.firstBadSnapshot());
        if (r.periodicAudits() > 0 || !r.periodicViolations().isEmpty()) {
            out.printf("Audits while trading       %d, violations %d%n", r.periodicAudits(), r.periodicViolations().size());
            r.periodicViolations().stream().limit(5).forEach(v -> out.println("    " + v));
        }
        if (r.livelockSymptom()) {
            out.printf("LIVELOCK symptom: %,d failed lock attempts for %,d trades (threads busy, little progress)%n",
                    r.settlementRetries(), r.trades());
        }
        if (r.deadlockReport() != null) out.print(r.deadlockReport());
        if (r.stuckTraders()) out.println("Trader threads did NOT stop when interrupted (blocked on locks or spinning).");
        if (r.auditError() != null) out.println("Final audit: " + r.auditError());
        out.println("Invariant violations       " + r.violations().size());
        for (String v : r.violations()) out.println("    " + v);
        out.println(r.clean()
                ? "VERDICT: all invariants hold, no anomalies, no deadlock."
                : "VERDICT: CONCURRENCY PROBLEMS DETECTED (see above).");
    }

    private static void printMarkets(AuditSnapshot audit, PrintStream out) {
        out.println("---- markets ----");
        for (BookSnapshot b : audit.books().values()) {
            out.printf("  %-5s last %10s  volume %,9d  bid %10s  ask %10s  resting orders %d%n", b.symbol(),
                    Money.format(b.referencePrice()), b.volume(),
                    b.bestBid() == 0 ? "-" : Money.format(b.bestBid()),
                    b.bestAsk() == 0 ? "-" : Money.format(b.bestAsk()),
                    b.bids().stream().mapToInt(l -> l.orders()).sum() + b.asks().stream().mapToInt(l -> l.orders()).sum());
        }
    }

    /**
     * Trading P&L of each trader: what they own now minus what they would own had they never
     * traded (their initial cash and shares), both valued at the last prices. Because cash and
     * shares are conserved, these numbers must add up to exactly zero: one more invariant.
     */
    private static void printProfitAndLoss(AuditSnapshot audit, MarketConfig market, PrintStream out) {
        List<Row> rows = new ArrayList<>();
        long sum = 0;
        for (AccountView a : audit.accounts().values()) {
            long pnl = a.totalCash() - market.initialCash();
            for (String s : market.symbols()) {
                pnl += (a.totalShares(s) - market.initialShares()) * audit.books().get(s).referencePrice();
            }
            rows.add(new Row(a.traderId(), strategyOf(a.traderId()), pnl));
            sum += pnl;
        }
        rows.sort(Comparator.comparingLong(Row::pnl).reversed());

        out.println("---- trading P&L vs. not trading at all (valued at last prices) ----");
        Map<String, long[]> byStrategy = new TreeMap<>();
        for (Row row : rows) {
            long[] acc = byStrategy.computeIfAbsent(row.strategy(), k -> new long[2]);
            acc[0] += row.pnl();
            acc[1]++;
        }
        byStrategy.forEach((k, v) -> out.printf("  %-12s average %15s  (%d traders)%n", k, Money.format(v[0] / v[1]), v[1]));
        int show = Math.min(3, rows.size());
        out.println("  best:  " + format(rows.subList(0, show)));
        out.println("  worst: " + format(rows.subList(rows.size() - show, rows.size())));
        out.println("  sum over all traders: " + Money.format(sum) + (sum == 0 ? " (zero-sum, as it must be)" : "  <-- should be 0!"));
    }

    private record Row(int id, String strategy, long pnl) {
    }

    private static String format(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        for (Row r : rows) {
            sb.append(String.format("T%d (%s) %s   ", r.id(), r.strategy(), Money.format(r.pnl())));
        }
        return sb.toString().trim();
    }
}
