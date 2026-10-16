package ro.upt.pcbe.bursa.engine;

/** Formatting helpers for amounts stored as cents. */
public final class Money {

    private Money() {
    }

    public static String format(long cents) {
        String sign = cents < 0 ? "-" : "";
        long abs = Math.abs(cents);
        return String.format("%s%,d.%02d", sign, abs / 100, abs % 100);
    }
}
