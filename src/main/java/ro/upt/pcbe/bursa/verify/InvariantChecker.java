package ro.upt.pcbe.bursa.verify;

import ro.upt.pcbe.bursa.api.AccountView;
import ro.upt.pcbe.bursa.api.AuditSnapshot;
import ro.upt.pcbe.bursa.api.BookSnapshot;
import ro.upt.pcbe.bursa.api.OrderView;
import ro.upt.pcbe.bursa.api.Side;
import ro.upt.pcbe.bursa.api.Trade;
import ro.upt.pcbe.bursa.engine.MarketConfig;
import ro.upt.pcbe.bursa.engine.Money;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks the properties that must hold at every consistent moment of the exchange.
 * If any of them fails, some update was lost, applied twice, or seen half-done:
 * the signature of a concurrency bug.
 *
 * <ol>
 *   <li>Cash is conserved: sum of (free + reserved) cash = initial total.</li>
 *   <li>Shares are conserved, per company.</li>
 *   <li>Nothing is negative (no overdrafts, no short selling).</li>
 *   <li>Escrow matches the book: each trader's reserved cash = sum over their active BUY
 *       orders of price * remaining; reserved shares = sum of their active SELL remaining.</li>
 *   <li>Every resting order is active and has remaining > 0.</li>
 *   <li>No book is crossed (best bid < best ask) after matching.</li>
 *   <li>The history is complete: as many recorded trades as executed ones, unique ids,
 *       positive quantities, and per-stock volume = sum of recorded quantities.</li>
 * </ol>
 */
public final class InvariantChecker {

    private static final int MAX_REPORTED = 25;

    private InvariantChecker() {
    }

    public static List<String> check(MarketConfig config, AuditSnapshot audit) {
        List<String> v = new ArrayList<>();

        // 1-3: conservation and non-negativity
        long totalCash = 0;
        Map<String, Long> totalShares = new HashMap<>();
        for (AccountView a : audit.accounts().values()) {
            totalCash += a.totalCash();
            if (a.cash() < 0) v.add("T" + a.traderId() + " has negative free cash " + Money.format(a.cash()));
            if (a.reservedCash() < 0) v.add("T" + a.traderId() + " has negative reserved cash " + Money.format(a.reservedCash()));
            for (String s : config.symbols()) {
                totalShares.merge(s, a.totalShares(s), Long::sum);
                if (a.shares().getOrDefault(s, 0L) < 0) v.add("T" + a.traderId() + " has negative " + s + " shares");
                if (a.reservedShares().getOrDefault(s, 0L) < 0) v.add("T" + a.traderId() + " has negative reserved " + s + " shares");
            }
        }
        if (totalCash != config.totalCash()) {
            v.add("cash not conserved: " + Money.format(totalCash) + " instead of " + Money.format(config.totalCash())
                    + " (difference " + Money.format(totalCash - config.totalCash()) + ")");
        }
        for (String s : config.symbols()) {
            long total = totalShares.getOrDefault(s, 0L);
            if (total != config.totalShares()) {
                v.add(s + " shares not conserved: " + total + " instead of " + config.totalShares());
            }
        }

        // 4-5: escrow vs resting orders
        Map<Integer, Long> expectedCash = new HashMap<>();
        Map<String, Long> expectedShares = new HashMap<>();   // key "trader:symbol"
        for (OrderView o : audit.activeOrders()) {
            if (!o.status().isActive() || o.remaining() <= 0) {
                v.add("inactive or empty order resting in book: " + o);
            }
            if (o.side() == Side.BUY) {
                expectedCash.merge(o.traderId(), o.price() * o.remaining(), Long::sum);
            } else {
                expectedShares.merge(o.traderId() + ":" + o.symbol(), (long) o.remaining(), Long::sum);
            }
        }
        for (AccountView a : audit.accounts().values()) {
            long exp = expectedCash.getOrDefault(a.traderId(), 0L);
            if (a.reservedCash() != exp) {
                v.add("T" + a.traderId() + " reserved cash " + Money.format(a.reservedCash())
                        + " but its open buy orders need " + Money.format(exp));
            }
            for (String s : config.symbols()) {
                long expS = expectedShares.getOrDefault(a.traderId() + ":" + s, 0L);
                long got = a.reservedShares().getOrDefault(s, 0L);
                if (got != expS) {
                    v.add("T" + a.traderId() + " reserved " + got + " " + s + " but its open sell orders need " + expS);
                }
            }
        }

        // 6: books
        for (BookSnapshot b : audit.books().values()) {
            if (b.isCrossed()) {
                v.add(b.symbol() + " book is crossed: bid " + Money.format(b.bestBid()) + " >= ask " + Money.format(b.bestAsk()));
            }
        }

        // 7: history
        List<Trade> history = audit.history();
        if (history.size() != audit.tradeCount()) {
            v.add("trade history has " + history.size() + " entries but " + audit.tradeCount() + " trades were executed");
        }
        Set<Long> ids = new HashSet<>();
        Map<String, Long> volume = new HashMap<>();
        int nulls = 0;
        for (Trade t : history) {
            if (t == null) {
                nulls++;
                continue;
            }
            if (!ids.add(t.id())) v.add("duplicate trade id " + t.id());
            if (t.quantity() <= 0) v.add("trade with non-positive quantity: " + t);
            volume.merge(t.symbol(), (long) t.quantity(), Long::sum);
        }
        if (nulls > 0) v.add("trade history contains " + nulls + " null entries (corrupted list)");
        for (BookSnapshot b : audit.books().values()) {
            long recorded = volume.getOrDefault(b.symbol(), 0L);
            if (recorded != b.volume()) {
                v.add(b.symbol() + " volume counter " + b.volume() + " != sum of recorded trades " + recorded);
            }
        }

        if (v.size() > MAX_REPORTED) {
            int extra = v.size() - MAX_REPORTED;
            v = new ArrayList<>(v.subList(0, MAX_REPORTED));
            v.add("... and " + extra + " more violations");
        }
        return v;
    }
}
