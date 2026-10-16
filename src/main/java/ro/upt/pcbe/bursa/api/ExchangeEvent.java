package ro.upt.pcbe.bursa.api;

/**
 * Domain events published by the exchange. Listeners receive them asynchronously, on the
 * event dispatcher thread, in the order the engine produced them.
 *
 * <p>This is the hook for Stage 2: a network layer or an AI agent can subscribe to these
 * events without the matching engine knowing about it.
 */
public sealed interface ExchangeEvent {

    String symbol();

    record OrderPlaced(OrderView order) implements ExchangeEvent {
        public String symbol() { return order.symbol(); }
    }

    record OrderModified(OrderView order) implements ExchangeEvent {
        public String symbol() { return order.symbol(); }
    }

    record OrderCancelled(OrderView order) implements ExchangeEvent {
        public String symbol() { return order.symbol(); }
    }

    record OrderRejected(int traderId, String symbol, String reason) implements ExchangeEvent {
    }

    record TradeExecuted(Trade trade) implements ExchangeEvent {
        public String symbol() { return trade.symbol(); }
    }
}
