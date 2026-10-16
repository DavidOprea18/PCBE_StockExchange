package ro.upt.pcbe.bursa.faulty.races;

import ro.upt.pcbe.bursa.api.AccountView;
import ro.upt.pcbe.bursa.api.AuditSnapshot;
import ro.upt.pcbe.bursa.api.BookSnapshot;
import ro.upt.pcbe.bursa.api.ExchangeEvent;
import ro.upt.pcbe.bursa.api.ExchangeListener;
import ro.upt.pcbe.bursa.api.ExchangeService;
import ro.upt.pcbe.bursa.api.OrderResult;
import ro.upt.pcbe.bursa.api.OrderView;
import ro.upt.pcbe.bursa.api.PriceLevel;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <b>INTENTIONALLY BROKEN.</b> Faulty variant #1: data races and data consistency.
 *
 * <p>Same structure and same matching rules as {@code CorrectExchange} (diff the two files!),
 * with five deliberate synchronization mistakes, each marked {@code // BUG Rn:}. None of them
 * is exotic; each is a "small optimization" or an oversight that looks harmless in code
 * review and passes every single-threaded test.
 *
 * <ul>
 *   <li><b>R1 - lost update on balances.</b> Settlement updates the buyer's and seller's
 *       accounts without their locks. The stock lock is held, but a trader's cash is shared by
 *       ALL stocks, and different stocks use different locks, so two settlements for the same
 *       trader can interleave inside {@code cash += amount} (read, add, write).</li>
 *   <li><b>R2 - check-then-act on funds.</b> "Is there enough cash? then reserve it" is done
 *       without the account lock, so two orders on two stocks can both pass the check and
 *       spend the same money: negative balances.</li>
 *   <li><b>R3 - modification during a trade.</b> A quantity-only modification takes a
 *       "fast path" that skips the stock lock. It writes the order's remaining quantity while
 *       a matching thread is filling that same order.</li>
 *   <li><b>R4 - shared non-thread-safe history.</b> All stocks append to one plain
 *       {@code ArrayList}, each under its own stock lock. Different locks = no mutual
 *       exclusion: lost entries, nulls, ArrayIndexOutOfBoundsException.</li>
 *   <li><b>R5 - torn, unsafely published snapshot.</b> Market data lives in one mutable object
 *       per stock, updated field by field, and read by traders with no lock and no volatile.
 *       A reader can see the new bid together with the old ask (a crossed book) or stale data.</li>
 * </ul>
 * Fixes: see {@code CorrectExchange} (pointers in each BUG comment) and DESIGN.md.
 */
public final class RacyExchange implements ExchangeService {

    /** BUG R5: a mutable "live" view shared with readers, instead of an immutable snapshot. */
    static final class LiveView {
        long version;             // plain fields: no volatile, no lock on the read side
        List<PriceLevel> bids = List.of();
        List<PriceLevel> asks = List.of();
        List<Trade> recent = List.of();
        long volume;
        long lastPrice;
    }

    static final class Market {
        final String symbol;
        final ReentrantLock lock;
        final OrderBook book;
        final ArrayDeque<Trade> recent = new ArrayDeque<>();
        long nextSequence;
        long volume;
        long lastPrice;
        final LiveView view = new LiveView();

        Market(String symbol, long listingPrice, boolean fair) {
            this.symbol = symbol;
            this.lock = new ReentrantLock(fair);
            this.book = new OrderBook(symbol);
            this.lastPrice = listingPrice;
        }
    }

    private final MarketConfig config;
    private final Map<String, Market> markets;
    private final Map<Integer, Account> accounts;
    private final ConcurrentHashMap<Long, Order> activeOrders = new ConcurrentHashMap<>();
    private final AtomicLong nextOrderId = new AtomicLong(1);
    private final AtomicLong nextTradeId = new AtomicLong(1);

    // BUG R4: an ArrayList is not thread-safe, and it is appended to under DIFFERENT locks
    // (one per stock). Fix: ConcurrentLinkedQueue (CorrectExchange#history), or one lock
    // shared by every writer.
    private final List<Trade> history = new ArrayList<>();

    private final EventDispatcher events = new EventDispatcher();
    private volatile boolean closed;

    public RacyExchange(MarketConfig config) {
        this.config = config;
        Map<String, Market> m = new LinkedHashMap<>();
        for (String symbol : config.symbols()) {
            m.put(symbol, new Market(symbol, config.listingPrice(symbol), config.fairLocks()));
        }
        Map<Integer, Account> a = new LinkedHashMap<>();
        for (int id = 0; id < config.traders(); id++) {
            a.put(id, new Account(id, config.initialCash(), config.symbols(), config.initialShares(), config.fairLocks()));
        }
        this.markets = Collections.unmodifiableMap(m);
        this.accounts = Collections.unmodifiableMap(a);
        for (Market market : markets.values()) {
            updateView(market);
        }
    }

    @Override
    public OrderResult placeOrder(int traderId, String symbol, Side side, long limitPrice, int quantity) {
        ensureOpen();
        Market market = markets.get(symbol);
        Account account = accounts.get(traderId);
        if (market == null || account == null || limitPrice <= 0 || quantity <= 0) {
            return OrderResult.rejected(-1, "invalid order");
        }
        long escrow = side == Side.BUY ? limitPrice * quantity : quantity;

        market.lock.lock();
        try {
            // BUG R2: check-then-act on the account WITHOUT account.lock(). The stock lock does
            // not protect the account: the same trader may be placing an order on another
            // stock (another lock) right now. Both threads read "enough cash", both subtract.
            // Fix: CorrectExchange#placeOrder takes account.lock() around tryReserve*.
            boolean reserved = side == Side.BUY
                    ? account.tryReserveCash(escrow)
                    : account.tryReserveShares(symbol, escrow);
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
            updateView(market);
            return OrderResult.accepted(order.id(), order.status(), filled);
        } finally {
            market.lock.unlock();
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

        if (newPrice == order.price()) {
            // BUG R3: "Only the quantity changes, the order stays at the same place in the book,
            // so we don't need the stock lock." Wrong: a matching thread may be filling this
            // very order (Order.fill does remaining -= qty) while we write remaining here.
            // The status check, the escrow adjustment and the write are not atomic with the
            // trade: lost fills, orders reborn after being FILLED, escrow that no longer
            // matches the book, IllegalStateException("bad fill") in the matcher.
            // This also violates the rule "no modification while a trade is in progress".
            // Fix: CorrectExchange#modifyOrder always holds market.lock (and re-checks status).
            if (!order.status().isActive()) {
                return OrderResult.rejected(orderId, "order no longer active");
            }
            long delta = order.side() == Side.BUY
                    ? newPrice * newQuantity - order.reservedAmount()
                    : newQuantity - order.reservedAmount();
            boolean ok = true;
            if (delta > 0) {
                ok = order.side() == Side.BUY ? account.tryReserveCash(delta) : account.tryReserveShares(order.symbol(), delta);
            } else if (delta < 0) {
                if (order.side() == Side.BUY) account.releaseCash(-delta);
                else account.releaseShares(order.symbol(), -delta);
            }
            if (!ok) return OrderResult.rejected(orderId, "insufficient funds for modification");
            order.setRemaining(newQuantity);
            return OrderResult.accepted(orderId, order.status(), 0);
        }

        // Price changes take the normal, locked path (same as CorrectExchange).
        market.lock.lock();
        try {
            if (!order.status().isActive()) {
                return OrderResult.rejected(orderId, "order no longer active");
            }
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
            int filled = matchAndSettle(market, order);
            if (order.remaining() > 0) market.book.add(order);
            updateView(market);
            return OrderResult.accepted(orderId, order.status(), filled);
        } finally {
            market.lock.unlock();
        }
    }

    @Override
    public boolean cancelOrder(int traderId, long orderId) {
        ensureOpen();
        Order order = activeOrders.get(orderId);
        if (order == null || order.traderId() != traderId) return false;
        Market market = markets.get(order.symbol());
        Account account = accounts.get(traderId);
        market.lock.lock();
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
            updateView(market);
            return true;
        } finally {
            market.lock.unlock();
        }
    }

    private int matchAndSettle(Market market, Order incoming) {
        List<Fill> fills = market.book.match(incoming);
        int total = 0;
        for (Fill fill : fills) {
            settle(market.symbol, fill);
            Trade trade = new Trade(nextTradeId.getAndIncrement(), market.symbol, fill.price(), fill.quantity(),
                    fill.buy().traderId(), fill.sell().traderId(), fill.buy().id(), fill.sell().id(), System.nanoTime());
            history.add(trade);                 // BUG R4 (see field declaration)
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
        // BUG R1: no account locks. "We already hold the stock lock" protects the book, not
        // the accounts. Trader T can be the seller on AAPL and the buyer on MSFT at the same
        // moment; both settlements do read-modify-write on T's cash concurrently and one of the
        // two updates is lost. Money disappears (or appears).
        // Fix: CorrectExchange#settle locks both accounts, in ascending id order.
        buyer.settleBuy(symbol, fill.quantity(), fill.buy().price(), fill.price());
        seller.settleSell(symbol, fill.quantity(), fill.price());
    }

    /**
     * BUG R5: updates the shared mutable view in place, field by field. Readers do not lock
     * and the fields are not volatile, so a reader can observe any mix of old and new values
     * (e.g. the new bids with the old asks: a crossed book that never existed), and the JMM
     * does not even promise it will ever see the new values.
     * Fix: CorrectExchange#publishSnapshot builds a NEW immutable BookSnapshot and publishes it
     * with ONE volatile write; readers see either the whole old one or the whole new one.
     */
    private void updateView(Market market) {
        LiveView v = market.view;
        v.version++;
        v.bids = market.book.levels(Side.BUY, 5);
        v.asks = market.book.levels(Side.SELL, 5);
        v.recent = List.copyOf(market.recent);
        v.volume = market.volume;
        v.lastPrice = market.lastPrice;
    }

    @Override
    public BookSnapshot snapshot(String symbol) {
        Market market = markets.get(symbol);
        if (market == null) throw new IllegalArgumentException("unknown symbol " + symbol);
        LiveView v = market.view;
        // BUG R5 (read side): several unsynchronized reads of fields that a writer is changing.
        return new BookSnapshot(symbol, v.version, v.bids, v.asks, v.recent, v.volume, v.lastPrice);
    }

    @Override
    public Optional<OrderView> order(long orderId) {
        Order order = activeOrders.get(orderId);
        if (order == null) return Optional.empty();
        Market market = markets.get(order.symbol());
        market.lock.lock();
        try {
            return order.status().isActive() ? Optional.of(order.view()) : Optional.empty();
        } finally {
            market.lock.unlock();
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
        return new ArrayList<>(history);    // BUG R4: unsynchronized copy of a list being appended to
    }

    @Override
    public long tradeCount() {
        return nextTradeId.get() - 1;
    }

    /**
     * Reads everything without locks. Only meaningful once all trader threads have stopped
     * (the simulation joins them first, which gives the needed happens-before).
     */
    @Override
    public AuditSnapshot audit() {
        Map<Integer, AccountView> views = new LinkedHashMap<>();
        for (Account a : accounts.values()) views.put(a.id(), a.view());
        List<OrderView> orders = new ArrayList<>();
        Map<String, BookSnapshot> books = new LinkedHashMap<>();
        for (Market m : markets.values()) {
            for (Order o : m.book.allOrders()) orders.add(o.view());
            books.put(m.symbol, new BookSnapshot(m.symbol, m.view.version,
                    m.book.levels(Side.BUY, Integer.MAX_VALUE), m.book.levels(Side.SELL, Integer.MAX_VALUE),
                    new ArrayList<>(m.recent), m.volume, m.lastPrice));
        }
        return new AuditSnapshot(views, orders, books, tradeCount(), new ArrayList<>(history));
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
        return "faulty-races";
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
