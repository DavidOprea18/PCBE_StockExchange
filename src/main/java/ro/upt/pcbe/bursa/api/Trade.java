package ro.upt.pcbe.bursa.api;

/**
 * One executed trade. Immutable (a record with final fields), so it can be shared between
 * threads freely once published.
 *
 * @param id          global trade id (unique, increasing)
 * @param symbol      company symbol
 * @param price       execution price per share, in cents
 * @param quantity    number of shares, n = min(offer, demand)
 * @param buyerId     trader id of the buyer
 * @param sellerId    trader id of the seller
 * @param buyOrderId  id of the buy order
 * @param sellOrderId id of the sell order
 * @param timestampNanos {@link System#nanoTime()} when the trade was executed
 */
public record Trade(long id, String symbol, long price, int quantity,
                    int buyerId, int sellerId, long buyOrderId, long sellOrderId,
                    long timestampNanos) {

    public long notional() {
        return price * quantity;
    }
}
