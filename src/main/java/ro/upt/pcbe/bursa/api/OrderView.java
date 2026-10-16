package ro.upt.pcbe.bursa.api;

/**
 * Immutable copy of an order's state at one moment. The engine's own {@code Order} objects
 * are mutable and lock-protected, so they are never handed out; callers get these copies.
 */
public record OrderView(long orderId, int traderId, String symbol, Side side,
                        long price, int remaining, int filled, OrderStatus status) {
}
