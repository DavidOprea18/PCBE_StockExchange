package ro.upt.pcbe.bursa.api;

/** Aggregated depth at one price: total shares and number of orders resting there. */
public record PriceLevel(long price, long quantity, int orders) {
}
