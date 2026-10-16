package ro.upt.pcbe.bursa.engine;

import ro.upt.pcbe.bursa.api.OrderStatus;
import ro.upt.pcbe.bursa.api.OrderView;
import ro.upt.pcbe.bursa.api.Side;

/**
 * An order inside the engine. <b>Not thread-safe.</b>
 *
 * <p>Thread-safety policy:
 * <ul>
 *   <li>{@code id, traderId, symbol, side} are {@code final}: safe to read from any thread once
 *       the object has been safely published (the engine publishes it through a
 *       {@code ConcurrentHashMap}, which provides the needed happens-before edge).</li>
 *   <li>All other fields are mutable and are <b>guarded by the lock of the stock the order
 *       belongs to</b>. Only the engine touches them, and only while holding that lock.</li>
 * </ul>
 * That is why outsiders never get an {@code Order}, only an immutable {@link OrderView}.
 */
public final class Order {

    private final long id;
    private final int traderId;
    private final String symbol;
    private final Side side;

    // ---- guarded by the stock's lock ----
    private long price;
    private int remaining;
    private int filled;
    private long sequence;          // time priority: lower = earlier
    private OrderStatus status = OrderStatus.OPEN;

    public Order(long id, int traderId, String symbol, Side side, long price, int quantity, long sequence) {
        if (price <= 0) throw new IllegalArgumentException("price must be positive");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        this.id = id;
        this.traderId = traderId;
        this.symbol = symbol;
        this.side = side;
        this.price = price;
        this.remaining = quantity;
        this.sequence = sequence;
    }

    public long id() { return id; }
    public int traderId() { return traderId; }
    public String symbol() { return symbol; }
    public Side side() { return side; }

    public long price() { return price; }
    public int remaining() { return remaining; }
    public int filled() { return filled; }
    public long sequence() { return sequence; }
    public OrderStatus status() { return status; }

    /** Executes {@code qty} shares of this order. */
    public void fill(int qty) {
        if (qty <= 0 || qty > remaining) {
            throw new IllegalStateException("bad fill " + qty + " on order " + id + " with remaining " + remaining);
        }
        remaining -= qty;
        filled += qty;
        status = remaining == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    /** Applies a modification. The order gets a new sequence number (loses time priority). */
    public void amend(long newPrice, int newRemaining, long newSequence) {
        if (newPrice <= 0 || newRemaining <= 0) throw new IllegalArgumentException("bad amendment");
        this.price = newPrice;
        this.remaining = newRemaining;
        this.sequence = newSequence;
    }

    /**
     * Sets only the remaining quantity, keeping price and priority. Used solely by the
     * faulty-races variant, to show what happens when a quantity change skips the lock.
     */
    public void setRemaining(int newRemaining) {
        this.remaining = newRemaining;
    }

    public void cancel() {
        status = OrderStatus.CANCELLED;
    }

    /** Cash (BUY) or shares (SELL) that this order currently holds in escrow. */
    public long reservedAmount() {
        return side == Side.BUY ? price * remaining : remaining;
    }

    public OrderView view() {
        return new OrderView(id, traderId, symbol, side, price, remaining, filled, status);
    }

    @Override
    public String toString() {
        return "Order#" + id + "[" + side + " " + remaining + " " + symbol + " @" + Money.format(price)
                + " by T" + traderId + " " + status + "]";
    }
}
