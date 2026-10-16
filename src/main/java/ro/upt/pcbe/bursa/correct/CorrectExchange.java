package ro.upt.pcbe.bursa.correct;

import ro.upt.pcbe.bursa.api.AccountView;
import ro.upt.pcbe.bursa.api.AuditSnapshot;
import ro.upt.pcbe.bursa.api.BookSnapshot;
import ro.upt.pcbe.bursa.api.ExchangeEvent;
import ro.upt.pcbe.bursa.api.ExchangeListener;
import ro.upt.pcbe.bursa.api.ExchangeService;
import ro.upt.pcbe.bursa.api.OrderResult;
import ro.upt.pcbe.bursa.api.OrderView;
import ro.upt.pcbe.bursa.api.Side;
import ro.upt.pcbe.bursa.api.Trade;
import ro.upt.pcbe.bursa.engine.Account;
import ro.upt.pcbe.bursa.engine.EventDispatcher;
import ro.upt.pcbe.bursa.engine.Fill;
import ro.upt.pcbe.bursa.engine.MarketConfig;
import ro.upt.pcbe.bursa.engine.Order;
import ro.upt.pcbe.bursa.engine.OrderBook;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The correct, thread-safe stock exchange.
 *
 * <h2>Synchronization design (summary; details in DESIGN.md)</h2>
 * <ol>
 *   <li><b>One lock per stock</b> ({@link Market#lock}). It guards that stock's order book,
 *       the mutable state of its orders, its counters and its trade list. Placing, modifying,
 *       cancelling and matching on a stock all run under it, so:
 *       <ul>
 *         <li>the book is never seen half-updated by another writer;</li>
 *         <li>"an order cannot be modified while a trade on that stock is in progress" holds by
 *             construction: a modification simply waits for the lock;</li>
 *         <li>different stocks trade in parallel (no global lock).</li>
 *       </ul></li>
 *   <li><b>One lock per account</b> ({@link Account#lock()}), because a trader's cash is shared
 *       by all stocks: two stocks may settle trades for the same trader at the same time.</li>
 *   <li><b>Lock hierarchy, to make deadlock impossible:</b> stock locks before account locks;
 *       several stock locks (only the audit takes more than one) in ascending symbol order;
 *       several account locks in ascending trader id. Every thread acquires locks in this one
 *       global order, so a cycle of waiting threads (the 4th Coffman condition, circular wait)
 *       cannot form.</li>
 *   <li><b>Lock-free reads of the market</b>: after every change, under the stock lock, we build
 *       an immutable {@link BookSnapshot} and store it in a {@code volatile} field. Readers just
 *       read that field: no lock, never blocked by writers, and the volatile write/read pair is
 *       a happens-before edge, so they see a completely built snapshot.</li>
 *   <li><b>Escrow</b>: cash/shares are reserved when an order is placed, inside the same critical
 *       section that inserts the order. Balances can never go negative, and at any moment
 *       "reserved = sum of open orders" (checked by the invariant checker).</li>
 *   <li><b>Fair locks</b> (configurable, on by default): waiting threads acquire in FIFO order,
 *       so no trader can be starved by others repeatedly barging in.</li>
 *   <li><b>Events</b> are queued under the lock (keeps them ordered) but delivered on another
 *       thread (no foreign code runs while we hold a lock); see {@link EventDispatcher}.</li>
 * </ol>
 */
public final class CorrectExchange implements ExchangeService {

    static final int SNAPSHOT_DEPTH = 5;
    static final int RECENT_TRADES = 20;

    /**
     * Per-stock state. Every non-final field is guarded by {@code lock}, except
     * {@code snapshot}, which is volatile and holds an immutable object.
     */
    static final class Market {
        final String symbol;
        final ReentrantLock lock;
        final OrderBook book;                       // guarded by lock (OrderBook is not thread-safe)
        final ArrayDeque<Trade> recent = new ArrayDeque<>(); // guarded by lock
        long nextSequence;                          // guarded by lock: time priority counter
        long volume;                                // guarded by lock
        long lastPrice;                             // guarded by lock
        long version;                               // guarded by lock
        /** Written only under lock; read by anyone without locking. Immutable value -> volatile is enough. */
        volatile BookSnapshot snapshot;

        Market(String symbol, long listingPrice, boolean fair) {
            this.symbol = symbol;
            this.lock = new ReentrantLock(fair);
            this.book = new OrderBook(symbol);
            this.lastPrice = listingPrice;
        }
    }

    private final MarketConfig config;

    // The maps/lists below are built in the constructor and never modified afterwards
    // (unmodifiable). They are assigned to final fields, and the JMM guarantees that a final
    // field's value (and everything reachable from it at the end of the constructor) is
    // visible to all threads without synchronization: safe publication through final.
    private final Map<String, Market> markets;
    private final List<Market> marketsInLockOrder;     // ascending symbol: the global stock-lock order
    private final Map<Integer, Account> accounts;
    private final List<Account> accountsInLockOrder;   // ascending id: the global account-lock order

    /**
     * Index of resting orders, used to find an order by id (modify/cancel/query) without
     * knowing its stock. ConcurrentHashMap: lookups from any thread without a global lock, and
     * put/get form a happens-before edge, so the order's final fields are safely published.
     * The order's mutable fields are still read only under its stock lock.
     */
    private final ConcurrentHashMap<Long, Order> activeOrders = new ConcurrentHashMap<>();

    /** Order ids are global (across stocks), so they need an atomic counter, not a stock lock. */
    private final AtomicLong nextOrderId = new AtomicLong(1);
    /** Incremented under a stock lock; atomic because different stocks increment it concurrently. */
    private final AtomicLong nextTradeId = new AtomicLong(1);
    /**
     * Global trade history. Lock-free, unbounded queue: many stocks append concurrently,
     * readers iterate a weakly consistent view without blocking writers. Appends happen under
     * the stock lock, so trades of one stock are recorded in execution order.
     */
    private final ConcurrentLinkedQueue<Trade> history = new ConcurrentLinkedQueue<>();

    private final EventDispatcher events = new EventDispatcher();

    /** Set once by close(); volatile so every thread sees it promptly. */
    private volatile boolean closed;

    public CorrectExchange(MarketConfig config) {
        this.config = config;
        Map<String, Market> m = new LinkedHashMap<>();
        TreeMap<String, Market> sorted = new TreeMap<>();
        for (String symbol : config.symbols()) {
            Market market = new Market(symbol, config.listingPrice(symbol), config.fairLocks());
            m.put(symbol, market);
            sorted.put(symbol, market);
        }
        Map<Integer, Account> a = new LinkedHashMap<>();
        List<Account> accountOrder = new ArrayList<>();
        for (int id = 0; id < config.traders(); id++) {
            Account acc = new Account(id, config.initialCash(), config.symbols(), config.initialShares(), config.fairLocks());
            a.put(id, acc);
            accountOrder.add(acc); // ids are created in ascending order
        }
        this.markets = Collections.unmodifiableMap(m);
        this.marketsInLockOrder = List.copyOf(sorted.values());
        this.accounts = Collections.unmodifiableMap(a);
        this.accountsInLockOrder = List.copyOf(accountOrder);
        // Initial snapshots. The Market objects are not shared with other threads yet (the
        // exchange is still being constructed), so no lock is needed here.
        for (Market market : markets.values()) {
            publishSnapshot(market);
        }
    }

    // =====================================================================================
    // Commands
    // =====================================================================================

    @Override
    public OrderResult placeOrder(int traderId, String symbol, Side side, long limitPrice, int quantity) {
        ensureOpen();
        Market market = markets.get(symbol);
        Account account = accounts.get(traderId);
        String invalid = validate(market, account, limitPrice, quantity);
        if (invalid != null) {
            return reject(traderId, symbol, invalid);
        }
        long escrow = escrowFor(side, limitPrice, quantity);
        if (escrow < 0) {
            return reject(traderId, symbol, "order value too large");
        }

        market.lock.lock();
        try {
            // Reserving inside the stock's critical section (not before it) matters: an audit,
            // which holds every stock lock, must never see money reserved for an order that is
            // not in a book yet. Lock order: stock -> account. Account lock held briefly.
            boolean reserved;
            account.lock().lock();
            try {
                reserved = side == Side.BUY
                        ? account.tryReserveCash(escrow)
                        : account.tryReserveShares(symbol, escrow);
            } finally {
                account.lock().unlock();
            }
            if (!reserved) {
                return reject(traderId, symbol, side == Side.BUY ? "insufficient cash" : "insufficient shares");
            }

            Order order = new Order(nextOrderId.getAndIncrement(), traderId, symbol, side,
                    limitPrice, quantity, market.nextSequence++);
            events.publish(new ExchangeEvent.OrderPlaced(order.view()));

            int filled = matchAndSettle(market, order);
            if (order.remaining() > 0) {
                market.book.add(order);
                activeOrders.put(order.id(), order);
            }
            publishSnapshot(market);
            return OrderResult.accepted(order.id(), order.status(), filled);
        } finally {
            market.lock.unlock();
        }
    }

    @Override
    public OrderResult modifyOrder(int traderId, long orderId, long newPrice, int newQuantity) {
        ensureOpen();
        Order order = activeOrders.get(orderId);
        if (order == null) {
            return OrderResult.rejected(orderId, "unknown or inactive order");
        }
        if (order.traderId() != traderId) {          // final field: safe to read without the lock
            return OrderResult.rejected(orderId, "not your order");
        }
        if (newPrice <= 0 || newQuantity <= 0) {
            return OrderResult.rejected(orderId, "price and quantity must be positive");
        }
        long newEscrow = escrowFor(order.side(), newPrice, newQuantity);
        if (newEscrow < 0) {
            return OrderResult.rejected(orderId, "order value too large");
        }
        Market market = markets.get(order.symbol());
        Account account = accounts.get(traderId);

        // Taking the stock lock is what implements "no modification while a trade is in
        // progress": trades on this stock only happen while this lock is held.
        market.lock.lock();
        try {
            // Re-check under the lock: between the map lookup and now, the order may have been
            // filled or cancelled by another thread. Check-then-act is only safe when the check
            // and the act are in the same critical section.
            if (!order.status().isActive()) {
                return OrderResult.rejected(orderId, "order no longer active");
            }
            long delta = newEscrow - order.reservedAmount();
            boolean ok = true;
            account.lock().lock();
            try {
                if (delta > 0) {
                    ok = order.side() == Side.BUY
                            ? account.tryReserveCash(delta)
                            : account.tryReserveShares(order.symbol(), delta);
                } else if (delta < 0) {
                    if (order.side() == Side.BUY) account.releaseCash(-delta);
                    else account.releaseShares(order.symbol(), -delta);
                }
            } finally {
                account.lock().unlock();
            }
            if (!ok) {
                // Nothing was changed: the order keeps its price, quantity and priority.
                return OrderResult.rejected(orderId, "insufficient funds for modification");
            }

            market.book.remove(order);                       // remove BEFORE changing the price
            order.amend(newPrice, newQuantity, market.nextSequence++);
            events.publish(new ExchangeEvent.OrderModified(order.view()));

            int filled = matchAndSettle(market, order);      // the new price may cross the book
            if (order.remaining() > 0) {
                market.book.add(order);
            }
            publishSnapshot(market);
            return OrderResult.accepted(orderId, order.status(), filled);
        } finally {
            market.lock.unlock();
        }
    }

    @Override
    public boolean cancelOrder(int traderId, long orderId) {
        ensureOpen();
        Order order = activeOrders.get(orderId);
        if (order == null || order.traderId() != traderId) {
            return false;
        }
        Market market = markets.get(order.symbol());
        Account account = accounts.get(traderId);
        market.lock.lock();
        try {
            if (!order.status().isActive()) {        // re-check under the lock
                return false;
            }
            market.book.remove(order);
            long escrow = order.reservedAmount();
            order.cancel();
            activeOrders.remove(orderId);
            account.lock().lock();
            try {
                if (order.side() == Side.BUY) account.releaseCash(escrow);
                else account.releaseShares(order.symbol(), escrow);
            } finally {
                account.lock().unlock();
            }
            events.publish(new ExchangeEvent.OrderCancelled(order.view()));
            publishSnapshot(market);
            return true;
        } finally {
            market.lock.unlock();
        }
    }

    // =====================================================================================
    // Matching and settlement. Caller MUST hold market.lock.
    // =====================================================================================

    /** Matches {@code incoming} against the book and settles every fill. Returns shares traded. */
    private int matchAndSettle(Market market, Order incoming) {
        assert market.lock.isHeldByCurrentThread();
        List<Fill> fills = market.book.match(incoming);
        int total = 0;
        for (Fill fill : fills) {
            settle(market.symbol, fill);
            Trade trade = new Trade(nextTradeId.getAndIncrement(), market.symbol, fill.price(), fill.quantity(),
                    fill.buy().traderId(), fill.sell().traderId(), fill.buy().id(), fill.sell().id(),
                    System.nanoTime());
            history.add(trade);
            market.recent.addLast(trade);
            if (market.recent.size() > RECENT_TRADES) {
                market.recent.removeFirst();
            }
            market.volume += fill.quantity();
            market.lastPrice = fill.price();
            total += fill.quantity();
            events.publish(new ExchangeEvent.TradeExecuted(trade));

            Order resting = fill.buy() == incoming ? fill.sell() : fill.buy();
            if (!resting.status().isActive()) {
                activeOrders.remove(resting.id());
            }
        }
        if (!incoming.status().isActive()) {
            activeOrders.remove(incoming.id());          // relevant when a modified order fills completely
        }
        return total;
    }

    /**
     * Moves cash and shares between buyer and seller. Both accounts are locked at the same
     * time, so the transfer is atomic for anyone who observes the accounts (e.g. an audit).
     * The locks are taken in ascending id order, never "buyer then seller": with the latter,
     * T1 settling (buyer A, seller B) and T2 settling (buyer B, seller A) on two different
     * stocks would each hold one lock and wait forever for the other. See faulty/liveness.
     */
    private void settle(String symbol, Fill fill) {
        Account buyer = accounts.get(fill.buy().traderId());
        Account seller = accounts.get(fill.sell().traderId());
        long buyLimit = fill.buy().price();
        if (buyer == seller) {                                  // self-trade: one lock
            buyer.lock().lock();
            try {
                buyer.settleBuy(symbol, fill.quantity(), buyLimit, fill.price());
                buyer.settleSell(symbol, fill.quantity(), fill.price());
            } finally {
                buyer.lock().unlock();
            }
            return;
        }
        Account first = buyer.id() < seller.id() ? buyer : seller;
        Account second = first == buyer ? seller : buyer;
        first.lock().lock();
        try {
            second.lock().lock();
            try {
                buyer.settleBuy(symbol, fill.quantity(), buyLimit, fill.price());
                seller.settleSell(symbol, fill.quantity(), fill.price());
            } finally {
                second.lock().unlock();
            }
        } finally {
            first.lock().unlock();
        }
    }

    /** Builds an immutable snapshot and publishes it with a volatile write. Caller holds the lock. */
    private void publishSnapshot(Market market) {
        market.version++;
        market.snapshot = new BookSnapshot(market.symbol, market.version,
                market.book.levels(Side.BUY, SNAPSHOT_DEPTH),
                market.book.levels(Side.SELL, SNAPSHOT_DEPTH),
                new ArrayList<>(market.recent), market.volume, market.lastPrice);
    }

    // =====================================================================================
    // Queries
    // =====================================================================================

    @Override
    public BookSnapshot snapshot(String symbol) {
        Market market = markets.get(symbol);
        if (market == null) throw new IllegalArgumentException("unknown symbol " + symbol);
        return market.snapshot;                 // one volatile read; no lock
    }

    @Override
    public Optional<OrderView> order(long orderId) {
        Order order = activeOrders.get(orderId);
        if (order == null) {
            return Optional.empty();
        }
        Market market = markets.get(order.symbol());
        market.lock.lock();                     // mutable order fields are guarded by the stock lock
        try {
            return order.status().isActive() ? Optional.of(order.view()) : Optional.empty();
        } finally {
            market.lock.unlock();
        }
    }

    @Override
    public AccountView account(int traderId) {
        Account account = accounts.get(traderId);
        if (account == null) throw new IllegalArgumentException("unknown trader " + traderId);
        account.lock().lock();                  // also needed for reads: visibility + consistency
        try {
            return account.view();
        } finally {
            account.lock().unlock();
        }
    }

    @Override
    public List<Trade> tradeHistory() {
        return List.copyOf(history);
    }

    @Override
    public long tradeCount() {
        return nextTradeId.get() - 1;
    }

    /**
     * Takes every lock in the global order (stocks by symbol, then accounts by id), copies the
     * whole state, releases everything. While it runs no trade can be half done anywhere, so
     * the result is a consistent cut of the system: exactly what invariant checks need.
     */
    @Override
    public AuditSnapshot audit() {
        List<Lock> held = new ArrayList<>();
        try {
            for (Market m : marketsInLockOrder) {
                m.lock.lock();
                held.add(m.lock);
            }
            for (Account a : accountsInLockOrder) {
                a.lock().lock();
                held.add(a.lock());
            }
            Map<Integer, AccountView> accountViews = new LinkedHashMap<>();
            for (Account a : accountsInLockOrder) {
                accountViews.put(a.id(), a.view());
            }
            List<OrderView> orders = new ArrayList<>();
            Map<String, BookSnapshot> books = new LinkedHashMap<>();
            for (Market m : marketsInLockOrder) {
                for (Order o : m.book.allOrders()) {
                    orders.add(o.view());
                }
                books.put(m.symbol, new BookSnapshot(m.symbol, m.version,
                        m.book.levels(Side.BUY, Integer.MAX_VALUE), m.book.levels(Side.SELL, Integer.MAX_VALUE),
                        new ArrayList<>(m.recent), m.volume, m.lastPrice));
            }
            return new AuditSnapshot(accountViews, orders, books, tradeCount(), List.copyOf(history));
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) {   // release in reverse order
                held.get(i).unlock();
            }
        }
    }

    // =====================================================================================
    // Misc
    // =====================================================================================

    @Override
    public MarketConfig config() {
        return config;
    }

    @Override
    public List<String> symbols() {
        return config.symbols();
    }

    @Override
    public void addListener(ExchangeListener listener) {
        events.addListener(listener);
    }

    @Override
    public String variantName() {
        return config.fairLocks() ? "correct" : "correct (non-fair locks)";
    }

    @Override
    public void close() {
        closed = true;
        events.close();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("exchange is closed");
    }

    private OrderResult reject(int traderId, String symbol, String reason) {
        events.publish(new ExchangeEvent.OrderRejected(traderId, symbol, reason));
        return OrderResult.rejected(-1, reason);
    }

    private static String validate(Market market, Account account, long price, int quantity) {
        if (market == null) return "unknown symbol";
        if (account == null) return "unknown trader";
        if (price <= 0) return "price must be positive";
        if (quantity <= 0) return "quantity must be positive";
        return null;
    }

    /** Cash for a BUY (price * qty), shares for a SELL; -1 on overflow. */
    static long escrowFor(Side side, long price, int quantity) {
        if (side == Side.SELL) return quantity;
        try {
            return Math.multiplyExact(price, (long) quantity);
        } catch (ArithmeticException overflow) {
            return -1;
        }
    }
}
