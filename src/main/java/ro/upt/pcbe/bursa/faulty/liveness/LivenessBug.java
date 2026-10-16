package ro.upt.pcbe.bursa.faulty.liveness;

/** Which liveness/performance bug the faulty-liveness exchange exhibits. */
public enum LivenessBug {
    /**
     * L1: settlement locks the BUYER's account first, then the SELLER's. Two trades on two
     * stocks with crossed roles (A buys from B, B buys from A) wait for each other forever.
     */
    DEADLOCK,
    /**
     * L2: "fix" for L1 using tryLock: if the second lock is busy, release the first and retry
     * immediately. Nobody blocks, but two threads can keep stepping back in lockstep and
     * make (almost) no progress.
     */
    LIVELOCK,
    /**
     * L3: one global lock for the whole exchange, readers included, held while doing slow
     * work (writing a compliance log). Correct, but stocks no longer trade in parallel and
     * every reader waits for every writer: throughput collapses.
     */
    GLOBAL_LOCK
}
