package ro.upt.pcbe.bursa.engine;

/**
 * Result of one match between a buy and a sell order, produced by {@link OrderBook#match}.
 * The engine turns each fill into a settlement (moving cash and shares) and a {@code Trade}.
 *
 * @param buy      the buy order (already updated by the fill)
 * @param sell     the sell order (already updated by the fill)
 * @param price    execution price: the price of the order that was resting in the book
 * @param quantity shares exchanged, min(buy remaining, sell remaining)
 */
public record Fill(Order buy, Order sell, long price, int quantity) {
}
