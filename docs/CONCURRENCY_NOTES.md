# Concurrency notes: oral exam cheat sheet

Short answers, each tied to where it shows up in **our** code. Read DESIGN.md for the full reasoning.

---

## Basics

**Q: What is a race condition? And a data race?**
A *race condition* is when correctness depends on the timing of threads. A *data race* (JMM term) is two accesses to the same variable from different threads, at least one a write, not ordered by happens-before. Data races are one cause of race conditions; check-then-act under separate locks is a race condition without a data race.
*Ours:* R1 (`cash += x` from two stocks), R3 (remaining modified during a fill), R4 (shared `ArrayList`).

**Q: Atomicity vs visibility?**
*Atomicity*: a compound action (read-modify-write, check-then-act, updating several fields) cannot be observed half done. *Visibility*: a write by one thread is eventually seen by others, in order. `volatile` gives visibility (and ordering) but **not** atomicity of `x++`. A lock gives both.
*Ours:* the snapshot needs only visibility of one reference (volatile is enough, the object is immutable); the account update needs atomicity (lock).

**Q: What is happens-before?**
The JMM's ordering relation: if A happens-before B, B sees A's effects. Sources: program order in one thread, unlock → later lock of the same lock, volatile write → later read, `Thread.start`, `Thread.join`, executor submission → task start, task end → `Future.get`, final-field freeze at the end of the constructor, concurrent-collection put → get.
*Ours:* DESIGN.md section 6 lists every edge we rely on.

**Q: `volatile` vs `AtomicLong` vs a lock?**
- `volatile`: visibility and ordering of single reads/writes; no compound atomicity. *Ours:* `snapshot`, `closed`.
- `AtomicLong`: lock-free atomic read-modify-write (CAS) on one variable. *Ours:* order and trade ids.
- lock: atomicity of any block, over several variables. *Ours:* the book, accounts.
- `LongAdder`: many writers, rare readers, sum is not a snapshot. *Ours:* statistics.

**Q: What is safe publication?**
Making an object visible to other threads together with its contents. Ways: final fields (and immutable objects), volatile reference, a lock, a concurrent collection, static initializer. Unsafe: assigning a mutable object to a plain shared field (R5).

**Q: What is thread confinement?**
Data used by only one thread needs no synchronization. *Ours:* each trader's `Random`, own order list and quotes; the observer's last-version map.

**Q: Why immutable objects?**
They are thread-safe by construction: no writes after construction, final fields guarantee visibility. *Ours:* every record in `api/` (snapshots, trades, views), with lists copied by `List.copyOf`.

---

## Locks

**Q: `ReentrantLock` vs `synchronized`?**
Both: mutual exclusion, reentrancy, same memory semantics. `ReentrantLock` adds fairness, `tryLock` (with timeout), `lockInterruptibly`, several `Condition`s, `isHeldByCurrentThread`. Cost: you must `unlock()` in `finally`. *Ours:* we need fairness (no starvation) and `tryLock` with timeout in the liveness audit, and the deadlock watchdog can see `ReentrantLock`s.

**Q: Fair vs non-fair lock?**
Fair: FIFO among waiters, no barging, so no starvation, lower throughput. Non-fair: a running thread can grab the lock ahead of waiters, higher throughput, possible starvation. *Ours:* fair by default; benchmark: non-fair ~1.9x more operations/s.

**Q: `ReadWriteLock` vs `StampedLock`?**
`ReentrantReadWriteLock`: many readers or one writer; good when reads are long and frequent; writer starvation possible in non-fair mode. `StampedLock`: adds optimistic reads (no locking, validate afterwards, retry on failure); not reentrant, no `Condition`. *Ours:* neither: readers get an immutable snapshot, which is cheaper than both (DESIGN.md section 7).

**Q: Why `unlock()` in `finally`?**
Any exception between lock and unlock would leave the lock held forever: every other thread then blocks (a deadlock with one participant).

**Q: Lock granularity?**
Coarse (one lock): simple, safe, no parallelism. Fine (per stock, per account): parallelism, but more locks means you must think about ordering. *Ours:* per stock + per account; `faulty-liveness` GLOBAL_LOCK shows the coarse extreme (~29x slower in trades/s).

---

## Liveness

**Q: The four conditions for deadlock (Coffman)?**
1. mutual exclusion, 2. hold and wait, 3. no preemption, 4. circular wait. Break any one and deadlock is impossible.
*Ours:* we break **circular wait** with a global lock order: stock locks before account locks, stocks by symbol, accounts by ascending id.

**Q: Show a deadlock in your project.**
`demo deadlock` / bug L1: settlement locks *buyer then seller*. Thread X settles AAPL (buyer B, seller A): holds B, wants A. Thread Y settles MSFT (buyer A, seller B): holds A, wants B. The watchdog prints the cycle.

**Q: Other ways to prevent deadlock?**
Lock ordering (ours); `tryLock` with timeout and back-off (breaks hold-and-wait, but beware livelock); one coarser lock (no nesting at all); avoid nested locks entirely (e.g. reserve in escrow first, settle each account separately); single-threaded ownership (actor per stock).

**Q: Deadlock vs livelock vs starvation?**
- deadlock: threads blocked forever, waiting for each other (no CPU used);
- livelock: threads keep running and reacting to each other but make no progress (CPU busy). *Ours:* L2, `tryLock` + immediate retry, thousands of retries per settlement;
- starvation: the system progresses, but some thread never gets its turn (e.g. barging non-fair locks, low priority). *Ours:* prevented with fair locks.

**Q: How do you detect a deadlock?**
`ThreadMXBean.findDeadlockedThreads()` (also sees `java.util.concurrent` locks), `jstack <pid>`, a thread dump from the IDE. *Ours:* `DeadlockWatchdog`.

**Q: How do you fix the livelock?**
Remove the need to retry (global lock order), or add randomized back-off so the competing threads fall out of step (like Ethernet's exponential back-off).

**Q: Can `lock()` be interrupted?**
No. A thread blocked in `lock()` ignores interrupts; that is why the deadlocked demo threads never stop. `lockInterruptibly()` and `tryLock(timeout)` can be interrupted.

---

## Collections and executors

**Q: Which concurrent collections did you use and why?**
- `ConcurrentHashMap`: order index; concurrent lookups, per-bin locking, happens-before between put and get.
- `ConcurrentLinkedQueue`: trade history; lock-free appends from many stocks, weakly consistent iteration.
- `CopyOnWriteArrayList`: listeners; written rarely, iterated on every event.
- `LinkedBlockingQueue` (inside the single-thread executor): event queue.
**Not** used for the order book: it is guarded by the stock lock, so plain `TreeMap`/`ArrayDeque` are faster and give atomic multi-step updates that no concurrent collection can.

**Q: Why is `Collections.synchronizedList` / `ConcurrentHashMap` not enough on its own?**
Each single operation is atomic, but a sequence (check, then put; read, then write back) is not. Compound actions still need a lock or an atomic method (`compute`, `merge`, `putIfAbsent`).

**Q: Why a thread pool instead of `new Thread()` per trader?**
Bounded, reusable threads, a standard shutdown protocol (`shutdown`, `shutdownNow`, `awaitTermination`), `Future`s for results and happens-before. *Ours:* fixed pool, one long-running task per trader.

**Q: `shutdown()` vs `shutdownNow()`?**
`shutdown`: no new tasks, running ones finish. `shutdownNow`: also interrupts running tasks. *Ours:* traders loop forever, so `shutdownNow` + interrupt-aware loop.

**Q: What do you do with `InterruptedException`?**
Never swallow it: either propagate it, or catch it, restore the flag (`Thread.currentThread().interrupt()`) and stop. *Ours:* `Trader.think()`, `EventDispatcher.close()`, scenario threads.

**Q: `CountDownLatch` vs `CyclicBarrier` vs `Semaphore`?**
Latch: one-shot gate, threads wait until a count reaches zero (*ours:* start gate in the deadlock scenario, watchdog notification). Barrier: reusable, N threads wait for each other at a point. Semaphore: limits concurrent access to N permits (e.g. "at most N orders in flight").

---

## Project-specific questions

**Q: How do you guarantee "no modification while a trade is in progress"?**
Matching runs entirely inside the stock lock, and `modifyOrder` needs the same lock, so a modification waits until the trade finishes. It then re-checks the order's status, because the order may have been filled meanwhile. R3 shows what happens with a lock-free "fast path".

**Q: Why do you need account locks if you already lock the stock?**
Because a trader's cash is shared across stocks, and two stocks are protected by two different locks: they can settle trades for the same trader at the same time (R1, R2).

**Q: Why are the snapshots consistent without a lock?**
They are immutable and built under the stock lock; the reference is published with a volatile write. A reader gets either the old snapshot or the new one, never a mix (R5 shows the mix).

**Q: How do you know your correct version has no races?**
Reasoning first (every shared field has a documented guard, every compound action is in one critical section, a global lock order). Then evidence: invariants checked during and after millions of operations, tens of millions of snapshot reads with zero torn ones, and the same tests catching the bugs in the faulty versions.

**Q: Why prices in cents?**
`double` cannot represent most decimal amounts exactly; sums drift, and "cash is conserved" could not be checked with `==`.

**Q: What would you change to handle 10x more traders?**
- measure first (profiler, lock contention: JFR "Java Monitor Blocked/Park" events);
- non-fair locks if fairness is not a requirement;
- shorter critical sections: build snapshots lazily or less often, move event construction out;
- one matching thread per stock with a `BlockingQueue` (no lock on the book, batching of commands);
- shard hot stocks or accounts; `LongAdder` for hot counters;
- for a distributed version (Stage 2): partition stocks across servers, keep each stock's matching single-writer, publish events to subscribers.

**Q: Virtual threads?**
Java 21 virtual threads make one thread per trader cheap and suit blocking I/O (Stage 2 networking). Our CPU-bound traders do not benefit much. Caveat in Java 21: a virtual thread blocked inside `synchronized` pins its carrier thread; `ReentrantLock` does not pin (fixed for `synchronized` in Java 24).

---

## Glosar român-englez

| Română | English |
|---|---|
| condiție de cursă | race condition |
| cursă de date | data race |
| excludere mutuală | mutual exclusion |
| secțiune critică | critical section |
| interblocare | deadlock |
| livelock (blocare activă) | livelock |
| înfometare | starvation |
| vizibilitate | visibility |
| atomicitate | atomicity |
| publicare sigură | safe publication |
| obiect imutabil | immutable object |
| confinare pe fir | thread confinement |
| ordine globală a lacătelor | global lock ordering |
| granularitatea lacătelor | lock granularity |
| lacăt echitabil | fair lock |
| fir de execuție | thread |
| bazin de fire | thread pool |
