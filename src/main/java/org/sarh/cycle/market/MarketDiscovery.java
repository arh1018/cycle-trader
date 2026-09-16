package org.sarh.cycle.market;

import com.fasterxml.jackson.databind.JsonNode;

import org.sarh.cycle.config.AppConfig;
import org.sarh.cycle.model.Market;
import org.sarh.cycle.rest.NobitexRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Finds every base coin that has both a live {@code <BASE>IRT} and {@code <BASE>USDT} book, ranks
 * them by 24h rial volume, and resolves order-size precision.
 *
 * <p>Scaled markets ({@code 1K_SHIBIRT}, {@code 1M_PEPEIRT}, ...) are excluded by default -- see
 * README "Known limitations". Handling them correctly needs a per-market multiplier applied
 * consistently across the IRT leg, the USDT leg and order sizing, which is future work.
 */
public final class MarketDiscovery {
    private static final Logger log = LoggerFactory.getLogger(MarketDiscovery.class);
    private static final Pattern SCALE_PREFIX = Pattern.compile("^(100K|10K|1K|1M|1B)_.+");

    public static final class Universe {
        public final Market usdtIrt;
        public final Map<String, Market> irtBySymbolBase; // base -> <BASE>IRT market
        public final Map<String, Market> usdtBySymbolBase; // base -> <BASE>USDT market
        public final List<String> bases; // ranked, filtered

        Universe(
                Market usdtIrt,
                Map<String, Market> irt,
                Map<String, Market> usdt,
                List<String> bases) {
            this.usdtIrt = usdtIrt;
            this.irtBySymbolBase = irt;
            this.usdtBySymbolBase = usdt;
            this.bases = bases;
        }
    }

    private final NobitexRestClient rest;
    private final AppConfig cfg;

    public MarketDiscovery(NobitexRestClient rest, AppConfig cfg) {
        this.rest = rest;
        this.cfg = cfg;
    }

    public Universe discover() {
        JsonNode books = rest.orderbookAll();
        Map<String, Double> precision = fetchPrecision();

        Set<String> liveIrt = new HashSet<>();
        Set<String> liveUsdt = new HashSet<>();
        Iterator<Map.Entry<String, JsonNode>> it = books.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String symbol = e.getKey();
            JsonNode book = e.getValue();
            if (!book.isObject() || !hasLiveBook(book)) {
                continue;
            }
            if (symbol.endsWith("IRT")) {
                liveIrt.add(symbol);
            } else if (symbol.endsWith("USDT")) {
                liveUsdt.add(symbol);
            }
        }

        Set<String> irtBases = new HashSet<>();
        for (String s : liveIrt) {
            irtBases.add(s.substring(0, s.length() - "IRT".length()));
        }
        Set<String> usdtBases = new HashSet<>();
        for (String s : liveUsdt) {
            usdtBases.add(s.substring(0, s.length() - "USDT".length()));
        }

        Set<String> candidateBases = new HashSet<>(irtBases);
        candidateBases.retainAll(usdtBases);
        candidateBases.removeIf(b -> b.equalsIgnoreCase("usdt"));
        if (cfg.excludePrefixed) {
            candidateBases.removeIf(b -> SCALE_PREFIX.matcher(b).matches());
        }
        for (String excluded : cfg.excludeBases) {
            candidateBases.removeIf(b -> b.equalsIgnoreCase(excluded));
        }

        log.info("{} candidate base coins with both IRT and USDT books", candidateBases.size());

        Map<String, double[]> statsByBase =
                fetchIrtStats(candidateBases); // base -> [latest, volumeDst]

        List<String> ranked =
                candidateBases.stream()
                        .filter(
                                b -> {
                                    double[] s = statsByBase.get(b);
                                    return s != null && s[1] >= cfg.minVolume24hRial && s[0] > 0;
                                })
                        .sorted(
                                (a, b) ->
                                        Double.compare(
                                                statsByBase.get(b)[1], statsByBase.get(a)[1]))
                        .limit(cfg.maxSymbols)
                        .toList();

        Map<String, Market> irtMarkets = new LinkedHashMap<>();
        Map<String, Market> usdtMarkets = new LinkedHashMap<>();
        for (String base : ranked) {
            double[] s = statsByBase.get(base);
            double step = precision.getOrDefault(base.toLowerCase(), 1e-6);
            irtMarkets.put(
                    base,
                    new Market(
                            base.toUpperCase() + "IRT",
                            base.toLowerCase(),
                            "rls",
                            step,
                            s[0],
                            s[1]));
            usdtMarkets.put(
                    base,
                    new Market(
                            base.toUpperCase() + "USDT", base.toLowerCase(), "usdt", step, 0, 0));
        }

        double usdtStep = precision.getOrDefault("usdt", 0.01);
        Market usdtIrt = new Market("USDTIRT", "usdt", "rls", usdtStep, 0, 0);

        log.info(
                "universe: {} triangles (x2 directions) after volume/limit filtering",
                ranked.size());
        return new Universe(usdtIrt, irtMarkets, usdtMarkets, ranked);
    }

    private static boolean hasLiveBook(JsonNode book) {
        JsonNode bids = book.path("bids");
        JsonNode asks = book.path("asks");
        return bids.isArray() && !bids.isEmpty() && asks.isArray() && !asks.isEmpty();
    }

    private Map<String, Double> fetchPrecision() {
        Map<String, Double> out = new HashMap<>();
        try {
            JsonNode options = rest.options();
            for (JsonNode coin : options.path("coins")) {
                String c = coin.path("coin").asText(null);
                String dp = coin.path("displayPrecision").asText(null);
                if (c != null && dp != null) {
                    try {
                        out.put(c.toLowerCase(), Double.parseDouble(dp));
                    } catch (NumberFormatException ignored) {
                        // skip unparsable precision
                    }
                }
            }
        } catch (Exception e) {
            log.warn("could not read /v2/options precision table: {}", e.toString());
        }
        return out;
    }

    /**
     * base -> [latest rial price, 24h rial volume], via /market/stats, bisecting a failing batch.
     */
    private Map<String, double[]> fetchIrtStats(Set<String> bases) {
        Map<String, double[]> out = new HashMap<>();
        List<String> list = new ArrayList<>(bases);
        fetchIrtStatsBatch(list, out);
        return out;
    }

    private void fetchIrtStatsBatch(List<String> bases, Map<String, double[]> out) {
        if (bases.isEmpty()) {
            return;
        }
        try {
            // The response echoes the code in the case it was sent ("BTC-rls" vs "btc-rls"), so
            // send lowercase and look up lowercase.
            String csv = String.join(",", bases.stream().map(String::toLowerCase).toList());
            JsonNode stats = rest.marketStats(csv, "rls");
            for (String base : bases) {
                JsonNode s = stats.path(base.toLowerCase() + "-rls");
                if (s.isMissingNode()) {
                    continue;
                }
                double latest = s.path("latest").asDouble(0);
                double volume = s.path("volumeDst").asDouble(0);
                out.put(base, new double[] {latest, volume});
            }
        } catch (Exception e) {
            if (bases.size() == 1) {
                log.debug("stats unavailable for {}: {}", bases.get(0), e.toString());
                return;
            }
            int mid = bases.size() / 2;
            fetchIrtStatsBatch(bases.subList(0, mid), out);
            fetchIrtStatsBatch(bases.subList(mid, bases.size()), out);
        }
    }
}
