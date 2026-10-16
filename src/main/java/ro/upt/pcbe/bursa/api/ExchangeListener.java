package ro.upt.pcbe.bursa.api;

/**
 * Subscriber to exchange events.
 *
 * <p>Called on the single event dispatcher thread, never while the engine holds a lock.
 * Implementations should be fast; a slow listener delays other listeners, not trading.
 */
@FunctionalInterface
public interface ExchangeListener {
    void onEvent(ExchangeEvent event);
}
