package ro.upt.pcbe.bursa.engine;

import ro.upt.pcbe.bursa.api.PriceLevel;
import ro.upt.pcbe.bursa.api.Side;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The order book of one stock, plus the matching algorithm. <b>Not thread-safe, on purpose.</b>
 *
 * <p>This class contains only the business logic (price-time priority matching). It has no
 * locks, no atomics and no volatile fields. The engine that owns it decides how it is
 * protected; in the correct engine every call happens while holding the stock's lock, so
 * plain {@link TreeMap} and {@link ArrayDeque} are enough. Keeping concurrency out of here
 * lets us unit-test matching deterministically on a single thread, and lets the three engine
 * variants share the exact same matching rules while differing only in synchronization.
 *
 * <p>Structure: for each side, a sorted map price -> FIFO queue of orders at that price.
 * Bids are sorted highest first, asks lowest first, so {@code firstEntry()} is always the
 * best price, and the head of each queue is the oldest order (time priority).
 */
public final class OrderBook {

    private final String symbol;
    private final NavigableMap<Long, ArrayDeque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, ArrayDeque<Order>> asks = new TreeMap<>();

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    private NavigableMap<Long, ArrayDeque<Order>> sideOf(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    /** Adds an order at the back of the queue for its price (it becomes the youngest there). */
    public void add(Order order) {
        if (!order.symbol().equals(symbol)) throw new IllegalArgumentException("wrong book for " + order);
        sideOf(order.side()).computeIfAbsent(order.price(), p -> new ArrayDeque<>()).addLast(order);
    }

    /**
     * Removes an order from the book. Must be called <i>before</i> changing the order's price,
     * since the price is how we find its level.
     *
     * @return true if the order was in the book
     */
    public boolean remove(Order order) {
        NavigableMap<Long, ArrayDeque<Order>> side = sideOf(order.side());
        ArrayDeque<Order> level = side.get(order.price());
        if (level == null || !level.remove(order)) {
            return false;
        }
        if (level.isEmpty()) {
            side.remove(order.price());
        }
        return true;
    }

    /**
     * Matches an incoming order (not yet in the book) against the opposite side.
     *
     * <p>Rules: a BUY matches asks with price <= its limit, a SELL matches bids with price >=
     * its limit. Better prices first, then older orders first. Each match trades
     * n = min(incoming remaining, resting remaining) shares at the <b>resting</b> order's price.
     * Fully filled resting orders are removed from the book. The incoming order is NOT added
     * to the book here; the caller adds it if something remains.
     *
     * @return the fills, in execution order (empty if nothing matched)
     */
    public List<Fill> match(Order incoming) {
        NavigableMap<Long, ArrayDeque<Order>> opposite = sideOf(incoming.side().opposite());
        List<Fill> fills = new ArrayList<>();
        while (incoming.remaining() > 0 && !opposite.isEmpty()) {
            Map.Entry<Long, ArrayDeque<Order>> best = opposite.firstEntry();
            long bestPrice = best.getKey();
            boolean crosses = incoming.side() == Side.BUY
                    ? incoming.price() >= bestPrice
                    : incoming.price() <= bestPrice;
            if (!crosses) {
                break;
            }
            ArrayDeque<Order> level = best.getValue();
            Order resting = level.peekFirst();
            int qty = Math.min(incoming.remaining(), resting.remaining());
            resting.fill(qty);
            incoming.fill(qty);
            fills.add(incoming.side() == Side.BUY
                    ? new Fill(incoming, resting, bestPrice, qty)
                    : new Fill(resting, incoming, bestPrice, qty));
            if (resting.remaining() == 0) {
                level.pollFirst();
                if (level.isEmpty()) {
                    opposite.pollFirstEntry();
                }
            }
        }
        return fills;
    }

    public long bestBid() {
        return bids.isEmpty() ? 0L : bids.firstKey();
    }

    public long bestAsk() {
        return asks.isEmpty() ? 0L : asks.firstKey();
    }

    public boolean isCrossed() {
        return !bids.isEmpty() && !asks.isEmpty() && bestBid() >= bestAsk();
    }

    /** Aggregated depth: up to {@code depth} best levels of one side (best first). */
    public List<PriceLevel> levels(Side side, int depth) {
        List<PriceLevel> out = new ArrayList<>(Math.min(depth, 16));
        for (Map.Entry<Long, ArrayDeque<Order>> e : sideOf(side).entrySet()) {
            if (out.size() >= depth) break;
            long qty = 0;
            for (Order o : e.getValue()) qty += o.remaining();
            out.add(new PriceLevel(e.getKey(), qty, e.getValue().size()));
        }
        return out;
    }

    /** Every order in the book (both sides). Used by audits. */
    public List<Order> allOrders() {
        List<Order> out = new ArrayList<>();
        for (ArrayDeque<Order> q : bids.values()) out.addAll(q);
        for (ArrayDeque<Order> q : asks.values()) out.addAll(q);
        return Collections.unmodifiableList(out);
    }

    public int size() {
        int n = 0;
        for (ArrayDeque<Order> q : bids.values()) n += q.size();
        for (ArrayDeque<Order> q : asks.values()) n += q.size();
        return n;
    }
}
