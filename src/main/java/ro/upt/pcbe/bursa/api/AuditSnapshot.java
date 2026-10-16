package ro.upt.pcbe.bursa.api;

import java.util.List;
import java.util.Map;

/**
 * A global picture of the exchange used to check invariants: every account, every active
 * order, and every book. In the correct engine it is taken atomically (all locks held), so
 * it is consistent even while traders are running.
 *
 * @param accounts     every account, by trader id
 * @param activeOrders every order still resting in a book
 * @param books        full-depth snapshot of every book
 * @param tradeCount   number of trades the engine reports having executed
 * @param history      the recorded trade history
 */
public record AuditSnapshot(Map<Integer, AccountView> accounts, List<OrderView> activeOrders,
                            Map<String, BookSnapshot> books, long tradeCount, List<Trade> history) {
}
