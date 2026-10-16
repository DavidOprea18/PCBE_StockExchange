package ro.upt.pcbe.bursa.api;

/** Which side of the market an order is on. */
public enum Side {
    BUY,
    SELL;

    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
