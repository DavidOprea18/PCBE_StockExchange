package ro.upt.pcbe.bursa.api;

import java.util.List;

/**
 * Immutable, internally consistent view of one stock's market: the top of the order book,
 * the last trades and the traded volume, all taken at the same instant.
 *
 * <p>Because every field is final and the lists are unmodifiable copies, a snapshot can be
 * read by any number of threads without locking. Consistency comes from how it is built
 * (under the stock's lock, see {@code CorrectExchange}) and published (volatile write).
 *
 * @param symbol       company symbol
 * @param version      increases by one on every change to this stock's book
 * @param bids         best bid levels, highest price first
 * @param asks         best ask levels, lowest price first
 * @param recentTrades most recent trades on this stock, oldest first
 * @param volume       total shares traded on this stock so far
 * @param referencePrice last trade price, or the listing price if nothing traded yet
 */
public record BookSnapshot(String symbol, long version, List<PriceLevel> bids, List<PriceLevel> asks,
                           List<Trade> recentTrades, long volume, long referencePrice) {

    /** Value returned by {@link #bestBid()} / {@link #bestAsk()} when that side is empty. */
    public static final long NO_PRICE = 0L;

    public BookSnapshot {
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
        recentTrades = List.copyOf(recentTrades);
    }

    public long bestBid() {
        return bids.isEmpty() ? NO_PRICE : bids.get(0).price();
    }

    public long bestAsk() {
        return asks.isEmpty() ? NO_PRICE : asks.get(0).price();
    }

    /** A crossed book (best bid >= best ask) must never be observed after matching. */
    public boolean isCrossed() {
        return !bids.isEmpty() && !asks.isEmpty() && bestBid() >= bestAsk();
    }

    public Trade lastTrade() {
        return recentTrades.isEmpty() ? null : recentTrades.get(recentTrades.size() - 1);
    }

    /** Mid price if both sides exist, otherwise the reference price. */
    public long midOrReference() {
        if (!bids.isEmpty() && !asks.isEmpty()) {
            return (bestBid() + bestAsk()) / 2;
        }
        return referencePrice;
    }
}
