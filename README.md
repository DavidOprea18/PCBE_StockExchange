# 📈 JavaBourse: a concurrent stock exchange in Java

> Many traders. One order book per stock. Zero race conditions (and we can prove the faulty versions have them).

JavaBourse is a simulated stock exchange built for **Stage 1** of the *Concurrent and Event-Based Programming* (PCBE/CEBP) lab at Politehnica University of Timișoara. Buyers and sellers are threads that read the shared market data and place, modify and cancel orders; a matching engine pairs them with price-time priority and settles every trade atomically.

The repository contains **three implementations of the same exchange**, behind one interface:

| Variant | Package | What it shows |
|---|---|---|
| `correct` | `correct` | Thread-safe, deadlock-free, starvation-free, with lock-free market data reads |
| `faulty-races` | `faulty.races` | 5 intentional data races: lost updates, check-then-act, modification during a trade, a shared `ArrayList`, torn snapshots |
| `faulty-liveness` | `faulty.liveness` | 3 intentional liveness bugs: deadlock, livelock, a global lock with slow work inside |

Because they share the same matching code, traders, tests and benchmark, you can `diff` a faulty file against `CorrectExchange.java` and see exactly which synchronization decision makes the difference.

## The problem

- Sellers offer a number of shares of a listed company at an asking price per share.
- Buyers bid for a number of shares at a price per share.
- When an offer and a demand match on price, `n = min(offer, demand)` shares are traded.
- Everyone can see the current offers and demands and the history of trades.
- Orders can be modified at any time, **except while a trade on those shares is in progress**.
- We implement the **simulation** variant: buyers and sellers are threads whose strategies use the shared information (order book and trade history).

## Quick start

Requirements: **JDK 21+** and **Maven 3.9+**. No runtime dependencies (JUnit 5 is test-only).

```bash
mvn package                       # compiles and runs the default test suite
java -jar target/javabourse.jar   # 10 s simulation of the correct exchange, with a live market view
```

### Run the variants

```bash
java -jar target/javabourse.jar simulate --variant=correct --traders=16 --duration=10
java -jar target/javabourse.jar simulate --variant=races
java -jar target/javabourse.jar simulate --variant=liveness --bug=DEADLOCK      # or LIVELOCK, GLOBAL_LOCK
```

All options: `--traders --symbols=AAPL,MSFT,TSLA,NVDA --duration (s) --seed --think (max µs between actions) --fair=true|false --live (ms, 0=off) --audit (ms) --config=sim.properties`. See `sim.properties` for a config-file example.

### Demos (faulty vs correct, side by side)

```bash
java -jar target/javabourse.jar demo races       # money disappears, history loses trades, snapshots tear
java -jar target/javabourse.jar demo deadlock    # two threads, two stocks, crossed roles -> deadlock report
java -jar target/javabourse.jar demo livelock    # thousands of retries, almost no progress
java -jar target/javabourse.jar bench            # throughput: per-stock locks vs global lock, fair vs non-fair
```

### Tests

```bash
mvn test            # matching rules + stress tests on the correct variant (must all pass)
mvn test -Pfaulty   # the SAME workloads against the faulty variants (must detect the bugs)
```

## What a run looks like

```
[  2.0s]   174,860 ops ( 86,581/s)   81,372 trades | AAPL 180.51/180.80 last 180.51 vol 110,476 | MSFT 419.34/420.53 ...
...
---- concurrency checks ----
Unexpected exceptions      0
Inconsistent snapshots     0 of 22,547,796 reads
Audits while trading       12, violations 0
Invariant violations       0
VERDICT: all invariants hold, no anomalies, no deadlock.
```

The same report for `--variant=races` shows money not conserved, escrow that no longer matches the book, missing trades in the history and crossed snapshots.

## Project structure

```
src/main/java/ro/upt/pcbe/bursa/
├── api/              ExchangeService interface, immutable views (snapshots, trades, events)
├── engine/           shared, NOT thread-safe business logic: OrderBook (matching), Order, Account;
│                     EventDispatcher (async, ordered event delivery)
├── correct/          CorrectExchange: the thread-safe implementation
├── faulty/races/     RacyExchange: data races, marked // BUG R1..R5
├── faulty/liveness/  LivenessExchange: deadlock / livelock / global lock, marked // BUG L1..L3
├── traders/          Random, Momentum and MarketMaker strategies (thread-confined state)
├── sim/              simulation runner, live view, final report, CLI options
├── verify/           InvariantChecker, SnapshotObserver (torn reads), DeadlockWatchdog
├── demo/             side-by-side demos, the crossing-trades deadlock scenario
└── bench/            throughput comparison
src/test/java/        matching tests, correct-variant stress tests, faulty-variant tests
docs/
├── DESIGN.md             architecture, assumptions, every synchronization decision and its alternatives
└── CONCURRENCY_NOTES.md  oral-exam cheat sheet (Q&A)
```

## How correctness is checked

`InvariantChecker` verifies, on a consistent global snapshot taken with every lock held:

1. total cash is conserved;
2. total shares of each company are conserved;
3. no balance or holding is negative;
4. escrow matches the book: reserved cash = Σ (price × remaining) of the trader's open buy orders, reserved shares = Σ remaining of open sell orders;
5. every resting order is active with remaining > 0;
6. no book is crossed after matching;
7. the trade history is complete and consistent with the volume counters.

In the correct variant this audit also runs **while** traders trade (every 200–500 ms). On top of that: a thread reads market snapshots non-stop looking for torn reads, a watchdog asks the JVM for deadlocked threads, the event subscriber must receive exactly one `TradeExecuted` per trade, and the traders' P&L must sum to exactly zero.

## Team

- Petruț Andrei
- Popa Alexandro Miodrag
- Oprea David Alexandru
- Mateaș Alexandru

Course: Concurrent and Event-Based Programming (PCBE/CEBP), 2026/2027, Universitatea Politehnica Timișoara, Facultatea de Automatică și Calculatoare.
