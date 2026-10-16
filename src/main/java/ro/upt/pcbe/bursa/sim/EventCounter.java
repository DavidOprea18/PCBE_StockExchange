package ro.upt.pcbe.bursa.sim;

import ro.upt.pcbe.bursa.api.ExchangeEvent;
import ro.upt.pcbe.bursa.api.ExchangeListener;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Event subscriber that counts events by type: a minimal example of event-based logic
 * reacting to the exchange without the engine knowing about it.
 *
 * <p>Written by the event dispatcher thread, read by the main thread, hence a
 * ConcurrentHashMap of LongAdders.
 */
public final class EventCounter implements ExchangeListener {

    private final ConcurrentHashMap<String, LongAdder> counts = new ConcurrentHashMap<>();

    @Override
    public void onEvent(ExchangeEvent event) {
        counts.computeIfAbsent(event.getClass().getSimpleName(), k -> new LongAdder()).increment();
    }

    public long count(Class<? extends ExchangeEvent> type) {
        LongAdder a = counts.get(type.getSimpleName());
        return a == null ? 0 : a.sum();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> out = new TreeMap<>();
        counts.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }
}
