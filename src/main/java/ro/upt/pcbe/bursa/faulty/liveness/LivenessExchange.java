package ro.upt.pcbe.bursa.faulty.liveness;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <b>INTENTIONALLY BROKEN.</b> Faulty variant #2: deadlock, livelock and performance.
 *
 * <p>Data is never corrupted here (every shared field is still protected by a lock); what
 * breaks is <i>progress</i>. The bug is chosen with {@link LivenessBug}; everything else is
 * identical to {@code CorrectExchange}. Bugs are marked {@code // BUG Ln:}.
 *
 * <p>All three bugs revolve around the same piece of "slow work": writing each settlement to
 * a compliance log, simulated by {@link #writeComplianceLog()} (~0.2 ms, like a disk or
 * network write). Doing slow work while holding a lock is itself the root of L3, and it is
 * what widens the window that makes L1 and L2 easy to observe.
 */
public final class LivenessExchange implements ExchangeService {

    private static final long COMPLIANCE_LOG_NANOS = 200_000;   // 0.2 ms

    static final class Market {
        final String symbol;
        final ReentrantLock lock;
        final OrderBook book;
        final ArrayDeque<Trade> recent = new ArrayDeque<>();
        long nextSequence;
        long volume;
        long lastPrice;
        long version;
        volatile BookSnapshot snapshot;

        Market(String symbol, long listingPrice, boolean fair) {
            this.symbol = symbol;
            this.lock = new ReentrantLock(fair);
            this.book = new OrderBook(symbol);
            this.lastPrice = listingPrice;
        }
    }

    private final LivenessBug bug;
    private final MarketConfig config;
    private final Map<String, Market> markets;
    private final List<Market> marketsInLockOrder;
    private final Map<Integer, Account> accounts;
    private final List<Account> accountsInLockOrder;
    private final ConcurrentHashMap<Long, Order> activeOrders = new ConcurrentHashMap<>();
    private final AtomicLong nextOrderId = new AtomicLong(1);
    private final AtomicLong nextTradeId = new AtomicLong(1);
    private final ConcurrentLinkedQueue<Trade> history = new ConcurrentLinkedQueue<>();
    private final EventDispatcher events = new EventDispatcher();
    private volatile boolean closed;

    /** BUG L3: one lock for everything (used only in GLOBAL_LOCK mode). */
    private final ReentrantLock globalLock = new ReentrantLock();

    /** Diagnostics for the demos. */
    private final LongAdder settlements = new LongAdder();
    private final LongAdder settlementRetries = new LongAdder();

    public LivenessExchange(MarketConfig config, LivenessBug bug) {
        this.bug = bug;
        this.config = config;
        Map<String, Market> m = new LinkedHashMap<>();
        TreeMap<String, Market> sorted = new TreeMap<>();
        for (String symbol : config.symbols()) {
            Market market = new Market(symbol, config.listingPrice(symbol), config.fairLocks());
            m.put(symbol, market);
            sorted.put(symbol, market);
        }
        Map<Integer, Account> a = new LinkedHashMap<>();
        List<Account> order = new ArrayList<>();
        for (int id = 0; id < config.traders(); id++) {
            Account acc = new Account(id, config.initialCash(), config.symbols(), config.initialShares(), config.fairLocks());
            a.put(id, acc);
            order.add(acc);
        }
        this.markets = Collections.unmodifiableMap(m);
        this.marketsInLockOrder = List.copyOf(sorted.values());
        this.accounts = Collections.unmodifiableMap(a);
        this.accountsInLockOrder = List.copyOf(order);
        for (Market market : markets.values()) publishSnapshot(market);
    }

    public LivenessBug bug() {
        return bug;
    }

    public long settlements() {
        return settlements.sum();
    }

    public long settlementRetries() {
        return settlementRetries.sum();
    }

    /** The lock that protects a stock: its own lock, or (BUG L3) the global one. */
    private Lock lockFor(Market market) {
        return bug == LivenessBug.GLOBAL_LOCK ? globalLock : market.lock;
    }

    // =====================================================================================

    @Override
    public OrderResult placeOrder(int traderId, String symbol, Side side, long limitPrice, int quantity) {
        ensureOpen();
        Market market = markets.get(symbol);
        Account account = accounts.get(traderId);
        if (market == null || account == null || limitPrice <= 0 || quantity <= 0) {
            return OrderResult.rejected(-1, "invalid order");
        }
        long escrow = side == Side.BUY ? limitPrice * quantity : quantity;
        Lock lock = lockFor(market);
        lock.lock();
        try {
            boolean reserved;
            account.lock().lock();
            try {
                reserved = side == Side.BUY ? account.tryReserveCash(escrow) : account.tryReserveShares(symbol, escrow);
            } finally {
                account.lock().unlock();
            }
            if (!reserved) {
                return OrderResult.rejected(-1, side == Side.BUY ? "insufficient cash" : "insufficient shares");
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
            lock.unlock();
        }
    }

    @Override
    public OrderResult modifyOrder(int traderId, long orderId, long newPrice, int newQuantity) {
        ensureOpen();
        Order order = activeOrders.get(orderId);
        if (order == null) return OrderResult.rejected(orderId, "unknown or inactive order");
        if (order.traderId() != traderId) return OrderResult.rejected(orderId, "not your order");
        if (newPrice <= 0 || newQuantity <= 0) return OrderResult.rejected(orderId, "bad values");
        Market market = markets.get(order.symbol());
        Account account = accounts.get(traderId);
        Lock lock = lockFor(market);
        lock.lock();
        try {
            if (!order.status().isActive()) return OrderResult.rejected(orderId, "order no longer active");
            long newEscrow = order.side() == Side.BUY ? newPrice * newQuantity : newQuantity;
            long delta = newEscrow - order.reservedAmount();
            boolean ok = true;
            account.lock().lock();
            try {
                if (delta > 0) {
                    ok = order.side() == Side.BUY ? account.tryReserveCash(delta) : account.tryReserveShares(order.symbol(), delta);
                } else if (delta < 0) {
                    if (order.side() == Side.BUY) account.releaseCash(-delta);
                    else account.releaseShares(order.symbol(), -delta);
                }
            } finally {
                account.lock().unlock();
            }
            if (!ok) return OrderResult.rejected(orderId, "insufficient funds for modification");
            market.book.remove(order);
            order.amend(newPrice, newQuantity, market.nextSequence++);
            events.publish(new ExchangeEvent.OrderModified(order.view()));
            int filled = matchAndSettle(market, order);
            if (order.remaining() > 0) market.book.add(order);
            publishSnapshot(market);
            return OrderResult.accepted(orderId, order.status(), filled);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean cancelOrder(int traderId, long orderId) {
        ensureOpen();
        Order order = activeOrders.get(orderId);
        if (order == null || order.traderId() != traderId) return false;
        Market market = markets.get(order.symbol());
        Account account = accounts.get(traderId);
        Lock lock = lockFor(market);
        lock.lock();
        try {
            if (!order.status().isActive()) return false;
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
            lock.unlock();
        }
    }

    private int matchAndSettle(Market market, Order incoming) {
        List<Fill> fills = market.book.match(incoming);
        int total = 0;
        for (Fill fill : fills) {
            settle(market.symbol, fill);
            Trade trade = new Trade(nextTradeId.getAndIncrement(), market.symbol, fill.price(), fill.quantity(),
                    fill.buy().traderId(), fill.sell().traderId(), fill.buy().id(), fill.sell().id(), System.nanoTime());
            history.add(trade);
            market.recent.addLast(trade);
            if (market.recent.size() > 20) market.recent.removeFirst();
            market.volume += fill.quantity();
            market.lastPrice = fill.price();
            total += fill.quantity();
            events.publish(new ExchangeEvent.TradeExecuted(trade));
            Order resting = fill.buy() == incoming ? fill.sell() : fill.buy();
            if (!resting.status().isActive()) activeOrders.remove(resting.id());
        }
        if (!incoming.status().isActive()) activeOrders.remove(incoming.id());
        return total;
    }

    private void settle(String symbol, Fill fill) {
        Account buyer = accounts.get(fill.buy().traderId());
        Account seller = accounts.get(fill.sell().traderId());
        long buyLimit = fill.buy().price();
        int qty = fill.quantity();
        long price = fill.price();
        if (buyer == seller) {
            buyer.lock().lock();
            try {
                buyer.settleBuy(symbol, qty, buyLimit, price);
                buyer.settleSell(symbol, qty, price);
            } finally {
                buyer.lock().unlock();
            }
            settlements.increment();
            return;
        }
        switch (bug) {
            case DEADLOCK -> settleBuyerFirst(symbol, buyer, seller, buyLimit, qty, price);
            case LIVELOCK -> settleWithPoliteRetry(symbol, buyer, seller, buyLimit, qty, price);
            case GLOBAL_LOCK -> settleUnderGlobalLock(symbol, buyer, seller, buyLimit, qty, price);
        }
        settlements.increment();
    }

    /**
     * BUG L1 (DEADLOCK): locks are taken in a <i>role</i> order (buyer, then seller) instead
     * of a <i>global</i> order. Thread 1 settles AAPL with buyer=A, seller=B: holds A, wants B.
     * Thread 2 settles MSFT with buyer=B, seller=A: holds B, wants A. All four Coffman
     * conditions hold (mutual exclusion, hold and wait, no preemption, circular wait) and both
     * threads block forever, together with every thread that later needs A, B, AAPL or MSFT.
     * The slow log write between the two lock() calls just makes the window wide.
     * Fix: CorrectExchange#settle locks the lower account id first, which breaks circular wait.
     */
    private void settleBuyerFirst(String symbol, Account buyer, Account seller, long buyLimit, int qty, long price) {
        buyer.lock().lock();
        try {
            writeComplianceLog();               // slow work while holding the first lock
            seller.lock().lock();
            try {
                buyer.settleBuy(symbol, qty, buyLimit, price);
                seller.settleSell(symbol, qty, price);
            } finally {
                seller.lock().unlock();
            }
        } finally {
            buyer.lock().unlock();
        }
    }

    /**
     * BUG L2 (LIVELOCK): tries to avoid the deadlock by never waiting for the second lock:
     * tryLock(), and if it fails, release the first lock and start over, <i>immediately</i>.
     * The two threads from L1 now both grab their first lock, both do the slow log write,
     * both fail tryLock on the other's lock, both back off, both retry at the same moment...
     * They are not blocked (states keep changing, CPU is busy) but no settlement completes:
     * a livelock. Progress only happens when timing noise happens to break the symmetry.
     * Fix: a global lock order (CorrectExchange#settle). If tryLock must be used, back off for
     * a <i>random</i> time before retrying, so the threads fall out of step.
     */
    private void settleWithPoliteRetry(String symbol, Account buyer, Account seller, long buyLimit, int qty, long price) {
        while (true) {
            buyer.lock().lock();
            try {
                writeComplianceLog();
                if (seller.lock().tryLock()) {
                    try {
                        buyer.settleBuy(symbol, qty, buyLimit, price);
                        seller.settleSell(symbol, qty, price);
                        return;
                    } finally {
                        seller.lock().unlock();
                    }
                }
            } finally {
                buyer.lock().unlock();
            }
            settlementRetries.increment();
            // no back-off: retry right away, in lockstep with the other thread
        }
    }

    /**
     * BUG L3 (GLOBAL_LOCK): we are already inside the global lock (see lockFor), so the
     * accounts are safe, but the slow log write happens inside it too: while one thread writes
     * the log, every other thread, for every stock, and every reader, waits.
     * Fix: per-stock locks (CorrectExchange) and moving slow work out of critical sections
     * (e.g. an ExchangeListener on the event thread could write the compliance log).
     */
    private void settleUnderGlobalLock(String symbol, Account buyer, Account seller, long buyLimit, int qty, long price) {
        writeComplianceLog();
        Account first = buyer.id() < seller.id() ? buyer : seller;
        Account second = first == buyer ? seller : buyer;
        first.lock().lock();
        try {
            second.lock().lock();
            try {
                buyer.settleBuy(symbol, qty, buyLimit, price);
                seller.settleSell(symbol, qty, price);
            } finally {
                second.lock().unlock();
            }
        } finally {
            first.lock().unlock();
        }
    }

    /**
     * Simulated slow I/O (~0.2 ms). parkNanos may return early (spurious wake-up, or a permit
     * left over by an earlier unpark from a lock hand-off), so we loop until the deadline, as
     * its Javadoc recommends. parkNanos does not throw InterruptedException.
     */
    private static void writeComplianceLog() {
        long deadline = System.nanoTime() + COMPLIANCE_LOG_NANOS;
        long left;
        while ((left = deadline - System.nanoTime()) > 0) {
            LockSupport.parkNanos(left);
        }
    }

    private void publishSnapshot(Market market) {
        market.version++;
        market.snapshot = new BookSnapshot(market.symbol, market.version,
                market.book.levels(Side.BUY, 5), market.book.levels(Side.SELL, 5),
                new ArrayList<>(market.recent), market.volume, market.lastPrice);
    }

    // =====================================================================================

    @Override
    public BookSnapshot snapshot(String symbol) {
        Market market = markets.get(symbol);
        if (market == null) throw new IllegalArgumentException("unknown symbol " + symbol);
        if (bug == LivenessBug.GLOBAL_LOCK) {
            // BUG L3: readers take the global lock too, so a trader that only wants to look at
            // the market waits behind every writer (and their slow log writes).
            // Fix: lock-free reads of an immutable volatile snapshot (CorrectExchange#snapshot).
            globalLock.lock();
            try {
                return market.snapshot;
            } finally {
                globalLock.unlock();
            }
        }
        return market.snapshot;
    }

    @Override
    public Optional<OrderView> order(long orderId) {
        Order order = activeOrders.get(orderId);
        if (order == null) return Optional.empty();
        Lock lock = lockFor(markets.get(order.symbol()));
        lock.lock();
        try {
            return order.status().isActive() ? Optional.of(order.view()) : Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public AccountView account(int traderId) {
        Account account = accounts.get(traderId);
        account.lock().lock();
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
     * Like CorrectExchange#audit, but with tryLock and a timeout: in DEADLOCK mode some locks
     * are held forever, and the audit must not join the deadlock.
     *
     * @throws IllegalStateException if some lock cannot be acquired within one second
     */
    @Override
    public AuditSnapshot audit() {
        List<Lock> held = new ArrayList<>();
        try {
            List<Lock> toTake = new ArrayList<>();
            if (bug == LivenessBug.GLOBAL_LOCK) toTake.add(globalLock);
            for (Market m : marketsInLockOrder) toTake.add(m.lock);
            for (Account a : accountsInLockOrder) toTake.add(a.lock());
            for (Lock l : toTake) {
                if (!l.tryLock(1, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("audit impossible: a lock is held forever (stuck threads?)");
                }
                held.add(l);
            }
            Map<Integer, AccountView> views = new LinkedHashMap<>();
            for (Account a : accountsInLockOrder) views.put(a.id(), a.view());
            List<OrderView> orders = new ArrayList<>();
            Map<String, BookSnapshot> books = new LinkedHashMap<>();
            for (Market m : marketsInLockOrder) {
                for (Order o : m.book.allOrders()) orders.add(o.view());
                books.put(m.symbol, new BookSnapshot(m.symbol, m.version,
                        m.book.levels(Side.BUY, Integer.MAX_VALUE), m.book.levels(Side.SELL, Integer.MAX_VALUE),
                        new ArrayList<>(m.recent), m.volume, m.lastPrice));
            }
            return new AuditSnapshot(views, orders, books, tradeCount(), List.copyOf(history));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("audit interrupted", e);
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) held.get(i).unlock();
        }
    }

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
        return "faulty-liveness (" + bug + ")";
    }

    @Override
    public void close() {
        closed = true;
        events.close();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("exchange is closed");
    }
}
