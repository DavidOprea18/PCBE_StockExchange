package ro.upt.pcbe.bursa.engine;

import ro.upt.pcbe.bursa.api.AccountView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A trader's cash and shares, including the amounts held in escrow for open orders.
 * <b>Not thread-safe by itself.</b>
 *
 * <p>Thread-safety policy: every mutable field below is <b>guarded by {@link #lock()}</b>.
 * Callers must hold that lock around every method call (reads included, for visibility).
 * The lock lives inside the account so that "which lock protects this data" is obvious, but
 * acquiring it is the caller's job, because some operations (a trade) must hold <i>two</i>
 * account locks at once, and only the caller can choose a safe order for them.
 *
 * <p>Amounts: cash in cents, shares as whole units. Escrow rules:
 * <ul>
 *   <li>placing a BUY of q shares at limit p moves q*p from {@code cash} to {@code reservedCash};</li>
 *   <li>placing a SELL of q shares moves q from {@code shares} to {@code reservedShares};</li>
 *   <li>a trade consumes escrow; cancelling or shrinking an order gives escrow back.</li>
 * </ul>
 * So {@code cash + reservedCash} is the trader's real wealth in cash, and escrow can never
 * go negative if every update goes through these methods under the lock.
 */
public final class Account {

    private final int id;
    private final ReentrantLock lock;

    // ---- guarded by lock ----
    private long cash;
    private long reservedCash;
    private final Map<String, Long> shares = new HashMap<>();
    private final Map<String, Long> reservedShares = new HashMap<>();

    public Account(int id, long initialCash, List<String> symbols, long initialShares, boolean fairLock) {
        this.id = id;
        this.lock = new ReentrantLock(fairLock);
        this.cash = initialCash;
        for (String s : symbols) {
            shares.put(s, initialShares);
            reservedShares.put(s, 0L);
        }
    }

    public int id() {
        return id;
    }

    /** The lock that guards this account's state. */
    public ReentrantLock lock() {
        return lock;
    }

    // ---------------- escrow ----------------

    /** Check-then-act: only atomic if the caller holds the lock. */
    public boolean tryReserveCash(long amount) {
        if (amount < 0) throw new IllegalArgumentException("negative amount");
        if (cash < amount) return false;
        cash -= amount;
        reservedCash += amount;
        return true;
    }

    public void releaseCash(long amount) {
        cash += amount;
        reservedCash -= amount;
    }

    /** Check-then-act: only atomic if the caller holds the lock. */
    public boolean tryReserveShares(String symbol, long qty) {
        if (qty < 0) throw new IllegalArgumentException("negative quantity");
        long free = shares.getOrDefault(symbol, 0L);
        if (free < qty) return false;
        shares.put(symbol, free - qty);
        reservedShares.merge(symbol, qty, Long::sum);
        return true;
    }

    public void releaseShares(String symbol, long qty) {
        shares.merge(symbol, qty, Long::sum);
        reservedShares.merge(symbol, -qty, Long::sum);
    }

    // ---------------- settlement ----------------

    /**
     * Buyer side of a trade: escrow was reserved at the buy limit, the trade executed at a
     * price that is never higher; the difference goes back to free cash.
     */
    public void settleBuy(String symbol, int qty, long buyLimit, long execPrice) {
        reservedCash -= (long) qty * buyLimit;
        cash += (long) qty * (buyLimit - execPrice);
        shares.merge(symbol, (long) qty, Long::sum);
    }

    /** Seller side of a trade: reserved shares leave, cash arrives. */
    public void settleSell(String symbol, int qty, long execPrice) {
        reservedShares.merge(symbol, (long) -qty, Long::sum);
        cash += (long) qty * execPrice;
    }

    // ---------------- reads ----------------

    public long cash() {
        return cash;
    }

    public long reservedCash() {
        return reservedCash;
    }

    public AccountView view() {
        return new AccountView(id, cash, reservedCash, shares, reservedShares);
    }
}
