package ro.upt.pcbe.bursa.api;

import ro.upt.pcbe.bursa.engine.MarketConfig;

import java.util.List;
import java.util.Optional;

/**
 * The stock exchange, as seen by traders. All three variants (correct, faulty-races,
 * faulty-liveness) implement this interface, so the same traders, tests and benchmark run
 * against each of them.
 *
 * <p>Thread safety contract: every method may be called concurrently from any number of
 * threads. (The faulty variants break this contract on purpose.)
 *
 * <p>Prices are in cents ({@code long}) to avoid floating point rounding, which would make
 * "money is conserved" impossible to check exactly.
 */
public interface ExchangeService extends AutoCloseable {

    MarketConfig config();

    List<String> symbols();

    /**
     * Places a limit order. It is matched immediately against the opposite side
     * (price-time priority); any remainder rests in the book. The cost (BUY) or the shares
     * (SELL) are reserved first; if that is impossible the order is rejected.
     */
    OrderResult placeOrder(int traderId, String symbol, Side side, long limitPrice, int quantity);

    /**
     * Changes the price and/or remaining quantity of an active order. Never runs while a
     * trade on the same stock is in progress: it waits until the trade completes. The
     * modified order loses its time priority and may match immediately.
     */
    OrderResult modifyOrder(int traderId, long orderId, long newPrice, int newQuantity);

    /** Cancels an active order and releases its reservation. Returns false if not active. */
    boolean cancelOrder(int traderId, long orderId);

    /** Current state of an order, or empty if it is unknown or no longer active. */
    Optional<OrderView> order(long orderId);

    /** Consistent view of one stock's book and recent trades. Never blocks in the correct variant. */
    BookSnapshot snapshot(String symbol);

    /** Copy of the full trade history. */
    List<Trade> tradeHistory();

    long tradeCount();

    AccountView account(int traderId);

    void addListener(ExchangeListener listener);

    /** Global snapshot for invariant checking. */
    AuditSnapshot audit();

    /** Short name of the variant, for reports. */
    String variantName();

    @Override
    void close();
}
