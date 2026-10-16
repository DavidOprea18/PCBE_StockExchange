package ro.upt.pcbe.bursa.api;

import java.util.Map;

/**
 * Immutable copy of a trader's account.
 *
 * <p>"Reserved" amounts are held in escrow for open orders: cash for open BUY orders
 * (remaining * limit price) and shares for open SELL orders (remaining). Escrow is what
 * makes overdrafts impossible: an order can only be placed if its cost can be reserved.
 *
 * @param traderId       owner
 * @param cash           free cash, in cents
 * @param reservedCash   cash locked by open buy orders, in cents
 * @param shares         free shares per symbol
 * @param reservedShares shares locked by open sell orders, per symbol
 */
public record AccountView(int traderId, long cash, long reservedCash,
                          Map<String, Long> shares, Map<String, Long> reservedShares) {

    public AccountView {
        shares = Map.copyOf(shares);
        reservedShares = Map.copyOf(reservedShares);
    }

    public long totalCash() {
        return cash + reservedCash;
    }

    public long totalShares(String symbol) {
        return shares.getOrDefault(symbol, 0L) + reservedShares.getOrDefault(symbol, 0L);
    }

    public long freeShares(String symbol) {
        return shares.getOrDefault(symbol, 0L);
    }
}
