package ro.upt.pcbe.bursa;

import ro.upt.pcbe.bursa.bench.Benchmark;
import ro.upt.pcbe.bursa.demo.Demos;
import ro.upt.pcbe.bursa.engine.MarketConfig;
import ro.upt.pcbe.bursa.faulty.liveness.LivenessBug;
import ro.upt.pcbe.bursa.sim.Report;
import ro.upt.pcbe.bursa.sim.Simulation;
import ro.upt.pcbe.bursa.sim.SimulationConfig;
import ro.upt.pcbe.bursa.sim.SimulationResult;
import ro.upt.pcbe.bursa.sim.Variant;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Command line entry point.
 *
 * <pre>
 * java -jar target/javabourse.jar simulate [--variant=correct|races|liveness] [--bug=DEADLOCK|LIVELOCK|GLOBAL_LOCK]
 *                                          [--traders=12] [--symbols=AAPL,MSFT,TSLA,NVDA] [--duration=10]
 *                                          [--seed=42] [--think=200] [--fair=true] [--live=1000] [--audit=500]
 *                                          [--config=sim.properties]
 * java -jar target/javabourse.jar demo races|deadlock|livelock
 * java -jar target/javabourse.jar bench [--traders=16] [--duration=5]
 * </pre>
 * Options can also come from a properties file ({@code --config=...}); command line wins.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 || args[0].startsWith("--") ? "simulate" : args[0];
        String sub = args.length > 1 && !args[1].startsWith("--") ? args[1] : null;
        Properties opts = parseOptions(args);
        int exit;
        try {
            exit = run(command, sub, opts);
        } catch (IllegalArgumentException e) {      // bad option value: message, not a stack trace
            System.err.println("error: " + e.getMessage());
            exit = 2;
        }
        System.exit(exit);   // also ends daemon threads left blocked by a deliberate deadlock
    }

    private static int run(String command, String sub, Properties opts) throws InterruptedException {
        return switch (command) {
            case "simulate" -> simulate(opts);
            case "demo" -> demo(sub, opts);
            case "bench" -> {
                Benchmark.run(System.out, intOpt(opts, "traders", 16), (long) (doubleOpt(opts, "duration", 5) * 1000));
                yield 0;
            }
            case "help", "-h", "--help" -> {
                usage();
                yield 0;
            }
            default -> {
                System.err.println("unknown command: " + command);
                usage();
                yield 2;
            }
        };
    }

    private static int simulate(Properties o) throws InterruptedException {
        Variant variant = Variant.parse(o.getProperty("variant", "correct"));
        MarketConfig market = MarketConfig.standard(intOpt(o, "traders", 12))
                .withFairLocks(Boolean.parseBoolean(o.getProperty("fair", "true")));
        if (o.containsKey("symbols")) {
            market = market.withSymbols(Arrays.stream(o.getProperty("symbols").split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).map(String::toUpperCase).toList());
        }
        SimulationConfig config = SimulationConfig.defaults(variant)
                .withMarket(market)
                .withBug(LivenessBug.valueOf(o.getProperty("bug", "DEADLOCK").toUpperCase()))
                .withDuration((long) (doubleOpt(o, "duration", 10) * 1000))
                .withSeed(Long.parseLong(o.getProperty("seed", "42")))
                .withThink(intOpt(o, "think", 200))
                .withLiveView(intOpt(o, "live", 1000))
                .withAudits(intOpt(o, "audit", variant == Variant.CORRECT ? 500 : 0));
        SimulationResult r = new Simulation(config, System.out).run();
        Report.print(r, market, System.out);
        return r.clean() ? 0 : 1;
    }

    private static int demo(String which, Properties o) throws InterruptedException {
        long millis = (long) (doubleOpt(o, "duration", 4) * 1000);
        boolean asExpected = switch (which == null ? "" : which) {
            case "races" -> Demos.races(System.out, millis);
            case "deadlock" -> Demos.deadlock(System.out);
            case "livelock" -> Demos.livelock(System.out, millis);
            default -> {
                System.err.println("demo name: races | deadlock | livelock");
                yield false;
            }
        };
        System.out.println(asExpected ? "\nDemo behaved as expected." : "\nDemo did NOT show the expected behaviour this time.");
        return asExpected ? 0 : 1;
    }

    private static Properties parseOptions(String[] args) throws IOException {
        Properties p = new Properties();
        List<String> flags = Arrays.stream(args).filter(a -> a.startsWith("--")).toList();
        for (String f : flags) {
            if (f.startsWith("--config=")) {
                try (Reader r = Files.newBufferedReader(Path.of(f.substring("--config=".length())))) {
                    p.load(r);
                }
            }
        }
        for (String f : flags) {
            int eq = f.indexOf('=');
            if (eq > 2) p.setProperty(f.substring(2, eq), f.substring(eq + 1));
        }
        return p;
    }

    private static int intOpt(Properties p, String key, int def) {
        return p.containsKey(key) ? Integer.parseInt(p.getProperty(key).trim()) : def;
    }

    private static double doubleOpt(Properties p, String key, double def) {
        return p.containsKey(key) ? Double.parseDouble(p.getProperty(key).trim()) : def;
    }

    private static void usage() {
        System.out.println("""
                JavaBourse - concurrent stock exchange simulation

                  simulate  run traders against an exchange variant
                      --variant=correct|races|liveness   (default correct)
                      --bug=DEADLOCK|LIVELOCK|GLOBAL_LOCK  (liveness only, default DEADLOCK)
                      --traders=12 --symbols=AAPL,MSFT,TSLA,NVDA --duration=10 (seconds)
                      --seed=42 --think=200 (max microseconds between actions)
                      --fair=true --live=1000 (ms, 0 = off) --audit=500 (ms, correct only)
                      --config=file.properties  (same keys, without the dashes)
                  demo races|deadlock|livelock   faulty variant vs correct, with evidence
                  bench [--traders=16] [--duration=5]   throughput comparison
                """);
    }
}
