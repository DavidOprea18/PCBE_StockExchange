package ro.upt.pcbe.bursa.api;

/**
 * Outcome of placing or modifying an order.
 *
 * @param accepted     false if the request was rejected (validation, insufficient funds...)
 * @param orderId      id of the order (or -1 if a placement was rejected)
 * @param status       status right after the call
 * @param filledNow    shares that traded immediately as a result of this call
 * @param rejectReason human readable reason, or null if accepted
 */
public record OrderResult(boolean accepted, long orderId, OrderStatus status, int filledNow,
                          String rejectReason) {

    public static OrderResult accepted(long orderId, OrderStatus status, int filledNow) {
        return new OrderResult(true, orderId, status, filledNow, null);
    }

    public static OrderResult rejected(long orderId, String reason) {
        return new OrderResult(false, orderId, null, 0, reason);
    }
}
