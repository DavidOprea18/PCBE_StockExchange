package ro.upt.pcbe.bursa.sim;

import ro.upt.pcbe.bursa.api.ExchangeService;
import ro.upt.pcbe.bursa.correct.CorrectExchange;
import ro.upt.pcbe.bursa.engine.MarketConfig;
import ro.upt.pcbe.bursa.faulty.liveness.LivenessBug;
import ro.upt.pcbe.bursa.faulty.liveness.LivenessExchange;
import ro.upt.pcbe.bursa.faulty.races.RacyExchange;

/** The three implementations of {@link ExchangeService}. */
public enum Variant {
    CORRECT("correct"),
    RACES("races"),
    LIVENESS("liveness");

    private final String cliName;

    Variant(String cliName) {
        this.cliName = cliName;
    }

    public String cliName() {
        return cliName;
    }

    public ExchangeService create(MarketConfig market, LivenessBug bug) {
        return switch (this) {
            case CORRECT -> new CorrectExchange(market);
            case RACES -> new RacyExchange(market);
            case LIVENESS -> new LivenessExchange(market, bug);
        };
    }

    public static Variant parse(String s) {
        for (Variant v : values()) {
            if (v.cliName.equalsIgnoreCase(s) || v.name().equalsIgnoreCase(s)) return v;
        }
        throw new IllegalArgumentException("unknown variant '" + s + "' (use correct, races or liveness)");
    }
}
