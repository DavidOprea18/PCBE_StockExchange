# Design

This document explains how the exchange works, which assumptions we made where the problem statement is silent, and, for every synchronization decision in the correct variant, **what it protects, why it is enough, and what the alternatives were**.

## 1. Architecture

```
   trader threads (fixed pool, one long-running task each)            observer / live view / audits
   Random | Momentum | MarketMaker                                    (read-only threads)
        │   placeOrder / modifyOrder / cancelOrder / order(id)               │ snapshot(), audit()
        ▼                                                                    ▼
 ┌──────────────────────────────── ExchangeService ────────────────────────────────┐
 │  per stock:  ReentrantLock ──guards──► OrderBook (TreeMap + FIFO queues)        │
 │                                         counters, recent trades                 │
 │                                         volatile BookSnapshot ◄── lock-free reads│
 │  per trader: ReentrantLock ──guards──► Account (cash, shares, escrow)           │
 │  global:     ConcurrentHashMap orderId→Order, AtomicLong ids,                   │
 │              ConcurrentLinkedQueue trade history                                │
 │  events:     queued under the stock lock ──► EventDispatcher thread ──► listeners│
 └──────────────────────────────────────────────────────────────────────────────────┘
```

The business logic (`OrderBook`, `Order`, `Account`) is deliberately **not thread-safe**: no locks, no atomics. Each exchange variant wraps it with its own synchronization. This separates *what* the exchange does (unit-tested single-threaded) from *how* it is made safe (the subject of the lab), and it makes the three variants directly comparable.

## 2. Assumptions and interpretation of the statement

| Topic | The statement says | Our decision | Why |
|---|---|---|---|
| Order type | buyers/sellers state a price | **limit orders** only | that is what "a number of shares at a certain price" is |
| Matching | offer and demand "correspond in price" | a buy at `b` matches a sell at `s` when `b ≥ s` | standard definition of crossing prices |
| Quantity | `n = min(offer, demand)` | exactly that; the remainder stays in the book (partial fill) | as stated |
| Execution price | not specified | price of the **resting** order (the one already in the book) | standard on real exchanges; it rewards the order that provided liquidity and makes the result independent of who arrived second |
| Priority | not specified | **price-time**: better price first, then older order first | fair and standard |
| Modification | allowed any time, except during a trade on those shares | the modification **waits** until the trade completes (it needs the stock lock, which matching holds) | waiting is the natural reading of "cannot happen during"; rejecting would push retries onto traders |
| Modification semantics | not specified | new price and new remaining quantity; the order **loses time priority** and may match immediately | otherwise one could jump the queue by modifying |
| Cancellation | not mentioned | supported (also releases escrow) | traders need it to manage stale orders; harmless extra |
| Funds | not mentioned | **escrow**: cash (BUY) or shares (SELL) are reserved when the order is accepted | makes overdrafts and short selling impossible by construction and gives a strong invariant to check |
| Self-trade | not mentioned | allowed | it violates no invariant and keeps matching simple |
| Money | not mentioned | `long` cents | `double` rounding would make "cash is conserved" impossible to check exactly |
| Shared information | everyone sees offers, demands, history | immutable `BookSnapshot` (top 5 levels per side + last 20 trades), full history via `tradeHistory()` | reads must be cheap and never block trading |

## 3. Thread-safety policy of the correct variant

Every piece of shared mutable state, and what makes access to it safe:

| State | Protected by | Why that is sufficient |
|---|---|---|
| `OrderBook` of a stock (TreeMaps, queues) | that stock's `ReentrantLock` | every read and write of the book happens inside the lock; mutual exclusion + the lock's happens-before (unlock → next lock) give atomicity and visibility |
| mutable fields of an `Order` (price, remaining, filled, status, sequence) | the lock of the order's stock | an order belongs to exactly one stock; all code paths touching it (match, modify, cancel, `order(id)`, audit) hold that lock |
| final fields of an `Order` (id, trader, symbol, side) | immutability + safe publication through `ConcurrentHashMap` | `put`/`get` on a CHM is a happens-before edge; final fields need nothing more |
| per-stock counters, `recent` trades | the stock lock | only touched while matching |
| `Account` cash, shares, escrow | that account's `ReentrantLock` | a trader's money is shared by all stocks, which use different stock locks, so the stock lock alone is NOT enough (that is bug R1) |
| `BookSnapshot` reference | `volatile` field, immutable object | built completely under the stock lock, then published by one volatile write; readers do one volatile read and see a fully constructed object, all-old or all-new, never a mix |
| order index | `ConcurrentHashMap` | lookups from any thread, no global lock |
| order ids, trade ids | `AtomicLong` | incremented by different stocks concurrently; a stock lock would not cover other stocks |
| trade history | `ConcurrentLinkedQueue` | lock-free appends from many stocks; readers copy a weakly consistent view without blocking writers |
| markets / accounts maps | built in the constructor, unmodifiable, assigned to `final` fields | the JMM guarantees visibility of final fields (and what they reference) after construction |
| `closed` flag | `volatile` | a simple flag written once, read by everyone |
| listeners | `CopyOnWriteArrayList` | written rarely, iterated on every event |
| trader state (random generator, own order ids, quotes) | **thread confinement** | each trader object is used only by the thread that runs it |
| statistics | `LongAdder` | many writers, rare readers; avoids contention on one counter |

### Why reservation happens *inside* the stock lock

`placeOrder` reserves the escrow inside the same critical section that inserts the order into the book. If it reserved first and locked the stock afterwards, an audit (which holds every lock) could observe money reserved for an order that is not in any book, and invariant 4 would fail on a perfectly valid state. Keeping "reserve" and "insert" in one critical section makes the pair atomic.

### Check-then-act is always re-checked under the lock

`modifyOrder` and `cancelOrder` find the order through the lock-free index, then take the stock lock and **re-check** `status().isActive()`. Between the lookup and the lock, another thread may have filled or cancelled the order. A check is only valid inside the critical section that acts on it.

## 4. Deadlock freedom

A deadlock needs all four Coffman conditions: mutual exclusion, hold-and-wait, no preemption, **circular wait**. We keep the first three (they come with locks) and make the fourth impossible with a **global lock order**:

1. stock locks before account locks;
2. if several stock locks are needed (only `audit()`), in ascending symbol order;
3. if several account locks are needed (settlement, audit), in ascending trader id.

Every thread acquires locks in increasing order of this hierarchy, so a cycle "T1 holds X and waits for Y, T2 holds Y and waits for X" would require one of them to acquire a lower lock while holding a higher one, which never happens.

The classic trap is settlement: "lock the buyer, then the seller" looks natural but is a *role* order, not a *global* order. With two stocks trading in opposite directions it deadlocks immediately (see `faulty-liveness`, bug L1, and `demo deadlock`).

Other potential cycles we closed:

- **listeners**: events are queued under the lock but delivered on another thread, so no foreign code runs while we hold a lock (a listener that called back into the exchange on another stock could otherwise create a cycle);
- **bounded queues**: the event queue is unbounded; a bounded one would block the engine under a stock lock whenever listeners fall behind (and a listener waiting for the engine would close the cycle).

## 5. Livelock and starvation

- **No livelock**: the correct variant never uses `tryLock`-and-retry or optimistic retry loops. Every lock acquisition blocks until it succeeds, and the hierarchy guarantees that it eventually does.
- **No starvation**: the engine's locks are **fair** by default (`new ReentrantLock(true)`): waiting threads acquire in FIFO order, so a thread cannot be overtaken forever by others barging in. Critical sections are short and bounded (no I/O, no sleeping, no waiting inside), so every waiter is served in bounded time.
- **Trade-off**: fairness costs throughput (more context switches, no barging). `--fair=false` switches to non-fair locks; they are still correct (deadlock-free, no races) but give no fairness guarantee. The benchmark shows the difference.

## 6. Visibility and the happens-before edges we rely on

| Edge | Where |
|---|---|
| unlock → subsequent lock of the same lock | everything guarded by stock/account locks |
| volatile write → volatile read of the same field | `BookSnapshot` publication, `closed` |
| `ConcurrentHashMap.put` → `get` | order index |
| final field semantics at end of constructor | markets, accounts, order identity, all records |
| `Executor.execute` → task start | event delivery to listeners |
| task end → `Future.get()` | the simulation joins traders before the lock-free audits of faulty variants |
| `CountDownLatch.countDown` → `await` | demo start gate, watchdog report |
| `Thread.start` / `Thread.join` | test workloads |

## 7. Market data reads: alternatives we considered

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **immutable snapshot + volatile** (chosen) | readers never block and never block writers; trivially consistent | writer rebuilds the top of book on each change (cheap: 5 levels, 20 trades) | best fit: reads are much more frequent than writes |
| `ReentrantReadWriteLock` | readers in parallel | readers still block writers (and vice versa); writer starvation with many readers unless fair | more contention for no benefit |
| `StampedLock` optimistic read | very cheap reads | must validate and retry; copying many fields under an optimistic read is error prone; not reentrant | good for a few primitive fields, overkill here |
| `ConcurrentSkipListMap` for the book | concurrent reads of the live book | a reader can still see a half-done match across several levels (no atomic multi-key view) | does not give consistency |
| same lock as writers | simple | every reader waits for every writer (bug L3) | rejected |

## 8. Granularity: per-stock locks

Per-stock locks let different companies trade in parallel and make the critical sections independent of the number of stocks. Compared with one global lock (see `faulty-liveness` GLOBAL_LOCK), this removes contention between unrelated stocks.

Limits, which are good oral-exam material:

- a single **hot stock** is still serialized: all its orders go through one lock. Scaling further needs a different design, e.g. a single matching thread per stock fed by a `BlockingQueue` (actor style: no lock at all on the book, the queue serializes) or sharding by price range;
- **account locks** are shared across stocks: one very active trader on all stocks becomes a contention point (short critical sections keep this small);
- with fewer CPU cores than busy stocks, extra parallelism cannot show; on our 2-core test machine the main win is that readers never wait.

Alternative we documented but did not implement: **one matching thread per stock** with a `BlockingQueue` of commands. The book would be confined to that thread (no lock at all), and commands would be processed one by one, so "no modification during a trade" would hold by construction too. We chose locks because Stage 1 is about synchronization primitives and because callers get synchronous results (`OrderResult`) without futures.

## 9. Events

`EventDispatcher` = a single-thread `ExecutorService` + `CopyOnWriteArrayList` of listeners.

- Publishing under the stock lock gives a **per-stock causal order** of events (events of one stock arrive in the order things happened to it).
- One consumer thread gives **FIFO delivery** to every listener.
- Listener exceptions are caught so one broken listener does not kill the dispatcher.
- On `close()` the queue is drained (bounded wait), so the simulation can check that the subscriber received exactly one `TradeExecuted` per trade.

This is the integration point for Stage 2 (network clients, remote APIs, agents).

## 10. Shutdown and interruption

- Traders loop on `!Thread.currentThread().isInterrupted()` and sleep their think time with `TimeUnit.sleep`. The simulation calls `shutdownNow()`, which interrupts them; `InterruptedException` is caught, the flag is **restored** with `Thread.currentThread().interrupt()`, and the loop exits. Nothing swallows an interrupt.
- `awaitTermination` bounds the wait; if traders do not stop (only possible in the faulty variants), the report says so instead of hanging.
- All helper threads are named daemon threads (`NamedThreadFactory`), so thread dumps are readable and a deliberately deadlocked demo cannot keep the JVM alive.

## 11. The faulty variants

### faulty-races (`RacyExchange`)

| Bug | Code smell | What breaks | How we detect it | Fix in `CorrectExchange` |
|---|---|---|---|---|
| R1 lost update | settlement without account locks ("we already hold the stock lock") | `cash += x` from two stocks interleaves: money disappears or appears | cash/shares conservation | `settle()` locks both accounts in id order |
| R2 check-then-act | "enough cash? then reserve" without the account lock | two stocks spend the same money: negative balances | non-negativity, escrow invariant | `placeOrder()` reserves under `account.lock()` |
| R3 modification during a trade | quantity-only change takes a lock-free "fast path" | `remaining` overwritten while the matcher fills the order: orders reborn after FILLED, escrow mismatch, shares created | escrow and conservation invariants; `modifyWhileMatching` test | `modifyOrder()` always holds the stock lock and re-checks status |
| R4 non-thread-safe collection | one `ArrayList` appended under different stock locks | lost or overwritten history entries, `ArrayIndexOutOfBoundsException` | history count vs trade counter, volume vs history | `ConcurrentLinkedQueue` |
| R5 torn read / unsafe publication | mutable view updated field by field, read without lock or volatile | a reader sees new bids with old asks: a crossed book that never existed | `SnapshotObserver` | new immutable `BookSnapshot` + one volatile write |

### faulty-liveness (`LivenessExchange`)

| Bug | What happens | Evidence | Fix |
|---|---|---|---|
| L1 DEADLOCK | settlement locks buyer then seller; crossed roles on two stocks form a cycle | `ThreadMXBean.findDeadlockedThreads()` report; traders never stop | global lock order (ascending account id) |
| L2 LIVELOCK | `tryLock` second lock, on failure release and retry **immediately** | thousands of failed attempts per completed settlement; threads busy, little progress | global lock order; if `tryLock` is unavoidable, randomized back-off |
| L3 GLOBAL_LOCK | one lock for all stocks and for readers, slow log write inside it | data stays correct, throughput collapses | per-stock locks, lock-free reads, slow work outside critical sections (e.g. in an event listener) |

The slow "compliance log" (`~0.2 ms`, simulated with `LockSupport.parkNanos` in a loop, since `parkNanos` may return early) is what turns L3 into a dramatic slowdown and what widens the windows of L1 and L2 so the demos are reproducible.

## 12. Verification strategy

1. **Unit tests** of matching rules, single-threaded and deterministic (`OrderBookTest`).
2. **Functional tests** of the correct exchange: escrow, refunds at better prices, modification semantics, rejections, events (`CorrectExchangeTest`).
3. **Stress tests**: 16 traders without think time, several seeds, invariants checked during and after trading, a reader thread looking for torn snapshots, a deadlock watchdog (`CorrectConcurrencyTest`).
4. **Targeted race test**: one thread modifies an order while another trades against it (`Workloads.modifyWhileMatching`).
5. **Negative tests**: the same workloads must expose each faulty variant (`FaultyVariantsTest`, `mvn test -Pfaulty`).

Passing stress tests is evidence, not proof: races are probabilistic. The proof is the reasoning in sections 3 to 6; the tests check that the code matches it.

## 13. Benchmark (measured)

`java -jar target/javabourse.jar bench`, 16 traders, 4 stocks, no think time, 4 s per variant, on a 2-core Linux VM, Java 21:

| Variant | operations/s | trades/s |
|---|---:|---:|
| correct (fair locks) | 144,666 | 66,947 |
| correct (non-fair locks) | 270,661 | 61,606 |
| faulty-liveness GLOBAL_LOCK | 33,326 | 2,286 |

Interpretation:

- The global lock is **~29x slower in trades/s**: every thread waits behind one writer that sleeps inside the lock, and readers wait too.
- Non-fair locks complete more operations (a running thread can re-acquire immediately, fewer context switches), the classic throughput-vs-fairness trade-off. We keep fair locks as the default because the requirement is "no starvation".
- Numbers vary between machines and runs; only large ratios are meaningful.

## 14. Review of the correct variant (what we checked)

- [x] Every shared mutable field has a documented guard (section 3); no field is accessed outside it.
- [x] Every compound action (check-then-act, read-modify-write, multi-field update) happens inside one critical section.
- [x] Lock acquisitions follow the hierarchy in section 4 everywhere: `placeOrder`, `modifyOrder`, `cancelOrder`, `settle`, `order`, `account`, `audit`.
- [x] No blocking calls, sleeps, I/O or foreign (listener) code inside a critical section.
- [x] No `tryLock`/retry loops (no livelock); fair locks (no starvation).
- [x] Every object handed to other threads is immutable (records with copied lists) or published through a happens-before edge.
- [x] `InterruptedException` is never swallowed: traders, dispatcher shutdown, scenario threads restore the flag or propagate it.
- [x] Overflow of `price × quantity` is checked (`Math.multiplyExact`).
- [x] Tests: 16 default tests pass; invariants hold during and after millions of operations; 0 torn snapshots in tens of millions of reads.

## 15. Ready for Stage 2

- `ExchangeService` is the boundary a server would expose over a network.
- `ExchangeListener` events (`OrderPlaced`, `OrderModified`, `OrderCancelled`, `OrderRejected`, `TradeExecuted`) are the event-based integration point.
- Traders only use the public interface, so they can become remote clients without changes to the engine.
