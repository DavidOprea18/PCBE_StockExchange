package ro.upt.pcbe.bursa.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static description of the market: which companies are listed, at what initial price,
 * how many traders there are and what each one starts with. Immutable.
 *
 * @param listingPrices  symbol -> initial reference price in cents (iteration order = listing order)
 * @param traders        number of trader accounts (ids 0 .. traders-1)
 * @param initialCash    starting cash per trader, in cents
 * @param initialShares  starting shares per trader, for every symbol
 * @param fairLocks      whether engine locks are fair (FIFO). See DESIGN.md, "Fairness".
 */
public record MarketConfig(Map<String, Long> listingPrices, int traders, long initialCash,
                           long initialShares, boolean fairLocks) {

    public MarketConfig {
        if (listingPrices.isEmpty()) throw new IllegalArgumentException("no symbols");
        if (traders < 1) throw new IllegalArgumentException("need at least one trader");
        if (initialCash < 0 || initialShares < 0) throw new IllegalArgumentException("negative endowment");
        // Defensive, order-preserving, unmodifiable copy.
        listingPrices = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(listingPrices));
    }

    public List<String> symbols() {
        return List.copyOf(listingPrices.keySet());
    }

    public long listingPrice(String symbol) {
        Long p = listingPrices.get(symbol);
        if (p == null) throw new IllegalArgumentException("unknown symbol " + symbol);
        return p;
    }

    /** Total cash in the system. Must never change. */
    public long totalCash() {
        return initialCash * traders;
    }

    /** Total shares of one company in the system. Must never change. */
    public long totalShares() {
        return initialShares * traders;
    }

    /** Default market: four companies, 1,000,000.00 cash and 1,000 shares of each per trader. */
    public static MarketConfig standard(int traders) {
        Map<String, Long> prices = new LinkedHashMap<>();
        prices.put("AAPL", 18_000L);
        prices.put("MSFT", 42_000L);
        prices.put("TSLA", 25_000L);
        prices.put("NVDA", 12_000L);
        return new MarketConfig(prices, traders, 100_000_000L, 1_000L, true);
    }

    public MarketConfig withFairLocks(boolean fair) {
        return new MarketConfig(listingPrices, traders, initialCash, initialShares, fair);
    }

    public MarketConfig withSymbols(List<String> symbols) {
        Map<String, Long> prices = new LinkedHashMap<>();
        long base = 10_000L;
        for (String s : symbols) {
            prices.put(s, listingPrices.getOrDefault(s, base));
            base += 2_500L;
        }
        return new MarketConfig(prices, traders, initialCash, initialShares, fairLocks);
    }
}
