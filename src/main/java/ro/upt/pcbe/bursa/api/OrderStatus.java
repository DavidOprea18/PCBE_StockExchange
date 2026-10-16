package ro.upt.pcbe.bursa.api;

/** Life cycle of an order: OPEN -> PARTIALLY_FILLED -> FILLED, or -> CANCELLED. */
public enum OrderStatus {
    OPEN,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED;

    /** An active order still rests in the book and can be matched, modified or cancelled. */
    public boolean isActive() {
        return this == OPEN || this == PARTIALLY_FILLED;
    }
}
