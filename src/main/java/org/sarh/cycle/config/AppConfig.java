package org.sarh.cycle.config;

import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed view over {@code config/config.yaml}, with credentials read from the environment.
 *
 * <p>Every field documents its YAML key and the default that applies when the key is absent.
 * Money amounts are in RIAL unless the name says otherwise; durations carry their unit in the name.
 */
public final class AppConfig {

    public enum Mode { PAPER, LIVE }

    // -- mode ---------------------------------------------------------------------------------

    /**
     * {@code mode} -- {@code paper} or {@code live}. Default {@code paper}.
     * Paper detects and simulates fills at the detector's prices; no order ever reaches the
     * exchange. Live places real orders and requires the API key pair below.
     */
    public final Mode mode;

    // -- nobitex ------------------------------------------------------------------------------

    /** {@code nobitex.rest_url} -- REST base URL. Default {@code https://apiv2.nobitex.ir}. */
    public final String restUrl;

    /** {@code nobitex.ws_url} -- Centrifugo websocket URL. Default {@code wss://ws.nobitex.ir/connection/websocket}. */
    public final String wsUrl;

    /**
     * Env {@code NOBITEX_API_KEY} -- public half of an Ed25519 API key pair. Default unset.
     * Never read from YAML. Resolved from the process environment first, then from a {@code .env}
     * file in the working directory. Required in live mode; optional in paper mode.
     */
    public final String apiKey;

    /**
     * Env {@code NOBITEX_API_SECRET} -- private half of the key pair, shown once at creation.
     * Default unset; same resolution as {@link #apiKey}. Looks identical to the public key
     * (44 chars base64); do not swap them.
     */
    public final String apiSecret;

    // -- universe -----------------------------------------------------------------------------

    /**
     * {@code universe.min_24h_rial_volume} -- drop a base coin whose {@code <BASE>IRT} book traded
     * less than this in the last 24h, in rial. Default {@code 5,000,000,000} (500M toman).
     * Thin books cannot absorb the notional and make the top-of-book estimate meaningless.
     */
    public final long minVolume24hRial;

    /**
     * {@code universe.max_symbols} -- keep at most this many base coins, ranked by 24h rial volume.
     * Default {@code 150}. Each base costs two websocket channels; Nobitex allows ~450 per connection.
     */
    public final int maxSymbols;

    /**
     * {@code universe.exclude_prefixed} -- skip scaled markets ({@code 1K_SHIB}, {@code 1M_PEPE},
     * ...). Default {@code true}. Their unit multiplier is not yet modelled across both legs.
     */
    public final boolean excludePrefixed;

    /**
     * {@code universe.exclude_bases} -- base coins never to trade, case-insensitive.
     * Default {@code ["USDT"]}. Stablecoins have no edge and USDT is the loop's own middle leg.
     */
    public final List<String> excludeBases;

    // -- fees ---------------------------------------------------------------------------------

    /**
     * {@code fees.taker_fee_irt} -- taker fee on rial-quoted legs ({@code <BASE>IRT}, {@code USDTIRT}),
     * as a fraction. Default {@code 0.0010} (0.10%). Set it to your real 30-day-volume tier.
     */
    public final double takerFeeIrt;

    /**
     * {@code fees.taker_fee_usdt} -- taker fee on USDT-quoted legs ({@code <BASE>USDT}), as a
     * fraction. Default {@code 0.0009} (0.09%).
     */
    public final double takerFeeUsdt;

    /**
     * {@code fees.slippage} -- extra per-leg haircut for filling beyond the touch, as a fraction.
     * Default {@code 0.0010} (0.10%). Applied on top of the fee to every leg's output.
     */
    public final double slippage;

    // -- detection ----------------------------------------------------------------------------

    /**
     * {@code detection.min_profit_ratio} -- minimum net loop profit (after fees and slippage on
     * all three legs) to act on, as a fraction. Default {@code 0.0015} (0.15%). Must be positive.
     */
    public final double minProfitRatio;

    /**
     * {@code detection.trade_notional_irt} -- rial committed to the first leg of every triangle.
     * Default {@code 5,000,000}. Must be at least {@link #minOrderRial}. Depth is not checked, so
     * keep it modest relative to the thinnest leg.
     */
    public final double tradeNotionalIrt;

    /**
     * {@code detection.min_order_rial} -- Nobitex's minimum for rial-quoted orders, in rial.
     * Default {@code 3,000,000}. Rial legs below this are rejected before an order is sent.
     */
    public final double minOrderRial;

    /**
     * {@code detection.min_order_usdt} -- Nobitex's minimum for USDT-quoted orders, in USDT.
     * Default {@code 11}. An opportunity whose middle leg is below this is discarded, because
     * leg 1 would fill and leg 2 would be rejected -- the worst outcome. Verify for your account.
     */
    public final double minOrderUsdt;

    /**
     * {@code detection.max_book_age_s} -- ignore a book not updated for longer than this, in
     * seconds (stored in milliseconds). Default {@code 10}. Guards against a stalled channel
     * making a stale quote look like an edge.
     */
    public final long maxBookAgeMillis;

    // -- execution ----------------------------------------------------------------------------

    /**
     * {@code execution.max_orders_per_10min} -- local ceiling on live orders per rolling 10 minutes.
     * Default {@code 60}. Nobitex's own shared limit is 300; a leg refused by this budget aborts
     * its triangle rather than risking an exchange-side block.
     */
    public final int maxOrdersPer10Min;

    /**
     * {@code execution.cross_by} -- how far through the touch a leg's limit price is placed, as a
     * fraction. Default {@code 0.005} (0.5%); must be in (0, 0.05]. Fills immediately like a
     * market order but cannot walk a thin book further than this.
     */
    public final double crossBy;

    /**
     * {@code execution.max_leg_slippage} -- log a warning when a fill's average price is further
     * than this from the price the opportunity was sized on, as a fraction. Default {@code 0.01}.
     */
    public final double maxLegSlippage;

    /**
     * {@code execution.leg_timeout_s} -- seconds to wait for a leg to fill before cancelling it and
     * aborting the loop. Default {@code 15}. Partial fills at cancel time are recorded exactly.
     */
    public final int legTimeoutS;

    /**
     * {@code execution.cooldown_s} -- after a triangle runs or aborts, ignore it for this many
     * seconds. Default {@code 30}. Without it one persistent quote fires on every book tick.
     */
    public final int cooldownS;

    /**
     * {@code execution.recovery_delay_s} -- when a live loop aborts with inventory stranded in
     * BASE or USDT, wait this many seconds, then sell it back to rial. Default {@code 600}.
     * The same delay separates retry attempts.
     */
    public final int recoveryDelayS;

    /**
     * {@code execution.recovery_max_attempts} -- how many unwind attempts before giving up and
     * reporting the episode as unrecovered for manual action. Default {@code 3}.
     */
    public final int recoveryMaxAttempts;

    // -- report -------------------------------------------------------------------------------

    /**
     * {@code report.csv_path} -- file receiving one row per completed episode (closed loop or
     * recovered abort). Default {@code reports/pnl.csv}. Parent directories are created; rows append.
     */
    public final Path reportCsvPath;

    /**
     * {@code report.summary_interval_s} -- log a per-path P&amp;L summary this often, in seconds.
     * Default {@code 300}. {@code 0} disables the periodic summary (one is still logged at shutdown).
     */
    public final int summaryIntervalS;

    // -- logging ------------------------------------------------------------------------------

    /** {@code logging.level} -- root log level name. Default {@code INFO}. */
    public final String logLevel;

    @SuppressWarnings("unchecked")
    private AppConfig(Map<String, Object> y, Map<String, String> dotenv) {
        this.mode = Mode.valueOf(str(y, "mode", "paper").toUpperCase());

        Map<String, Object> nobitex = section(y, "nobitex");
        this.restUrl = str(nobitex, "rest_url", "https://apiv2.nobitex.ir");
        this.wsUrl = str(nobitex, "ws_url", "wss://ws.nobitex.ir/connection/websocket");
        this.apiKey = envOrDotenv("NOBITEX_API_KEY", dotenv);
        this.apiSecret = envOrDotenv("NOBITEX_API_SECRET", dotenv);

        Map<String, Object> universe = section(y, "universe");
        this.minVolume24hRial = num(universe, "min_24h_rial_volume", 5_000_000_000d).longValue();
        this.maxSymbols = num(universe, "max_symbols", 150d).intValue();
        this.excludePrefixed = bool(universe, "exclude_prefixed", true);
        this.excludeBases = (List<String>) universe.getOrDefault("exclude_bases", List.of("USDT"));

        Map<String, Object> fees = section(y, "fees");
        this.takerFeeIrt = num(fees, "taker_fee_irt", 0.0013);
        this.takerFeeUsdt = num(fees, "taker_fee_usdt", 0.0009);
        this.slippage = num(fees, "slippage", 0.0010);

        Map<String, Object> detection = section(y, "detection");
        this.minProfitRatio = num(detection, "min_profit_ratio", 0.0015);
        this.tradeNotionalIrt = num(detection, "trade_notional_irt", 5_000_000);
        this.minOrderRial = num(detection, "min_order_rial", 3_000_000);
        this.minOrderUsdt = num(detection, "min_order_usdt", 11);
        this.maxBookAgeMillis = (long) (num(detection, "max_book_age_s", 10d) * 1000);

        Map<String, Object> execution = section(y, "execution");
        this.maxOrdersPer10Min = num(execution, "max_orders_per_10min", 60d).intValue();
        this.crossBy = num(execution, "cross_by", 0.005);
        this.maxLegSlippage = num(execution, "max_leg_slippage", 0.01);
        this.legTimeoutS = num(execution, "leg_timeout_s", 15d).intValue();
        this.cooldownS = num(execution, "cooldown_s", 30d).intValue();
        this.recoveryDelayS = num(execution, "recovery_delay_s", 600d).intValue();
        this.recoveryMaxAttempts = num(execution, "recovery_max_attempts", 3d).intValue();

        Map<String, Object> report = section(y, "report");
        this.reportCsvPath = Path.of(str(report, "csv_path", "reports/pnl.csv"));
        this.summaryIntervalS = num(report, "summary_interval_s", 300d).intValue();

        Map<String, Object> logging = section(y, "logging");
        this.logLevel = str(logging, "level", "INFO");

        validate();
    }

    private void validate() {
        if (mode == Mode.LIVE && (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank())) {
            throw new IllegalStateException(
                    "mode=live requires NOBITEX_API_KEY and NOBITEX_API_SECRET in the environment");
        }
        if (tradeNotionalIrt < minOrderRial) {
            throw new IllegalStateException(String.format(
                    "detection.trade_notional_irt (%.0f) is below detection.min_order_rial (%.0f); "
                            + "every order would be rejected", tradeNotionalIrt, minOrderRial));
        }
        if (minProfitRatio <= 0) {
            throw new IllegalStateException("detection.min_profit_ratio must be positive");
        }
        if (crossBy <= 0 || crossBy > 0.05) {
            throw new IllegalStateException("execution.cross_by should be in (0, 0.05]");
        }
        if (legTimeoutS <= 0 || recoveryDelayS < 0 || recoveryMaxAttempts < 1) {
            throw new IllegalStateException(
                    "execution.leg_timeout_s must be > 0, recovery_delay_s >= 0, recovery_max_attempts >= 1");
        }
    }

    public static AppConfig load(Path path) throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = new FileInputStream(path.toFile())) {
            Map<String, Object> y = yaml.load(in);
            return new AppConfig(y == null ? Map.of() : y, readDotenv(Path.of(".env")));
        }
    }

    /** Minimal {@code KEY=value} parser: comments and blank lines skipped, optional surrounding quotes. */
    static Map<String, String> readDotenv(Path file) {
        Map<String, String> out = new HashMap<>();
        if (!Files.isRegularFile(file)) return out;
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring("export ".length()).strip();
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                        && value.charAt(value.length() - 1) == value.charAt(0)) {
                    value = value.substring(1, value.length() - 1);
                }
                out.put(key, value);
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + file + ": " + e.getMessage(), e);
        }
        return out;
    }

    private static String envOrDotenv(String key, Map<String, String> dotenv) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) return v;
        v = dotenv.get(key);
        return v == null || v.isBlank() ? null : v;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> y, String key) {
        Object v = y.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : v.toString();
    }

    private static Double num(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        return v == null ? def : ((Number) v).doubleValue();
    }

    private static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        return v == null ? def : (Boolean) v;
    }
}
