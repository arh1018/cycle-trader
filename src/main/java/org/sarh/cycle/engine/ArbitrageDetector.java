package org.sarh.cycle.engine;

import org.sarh.cycle.config.AppConfig;
import org.sarh.cycle.model.Opportunity;
import org.sarh.cycle.model.OrderBookTop;
import org.sarh.cycle.model.Side;
import org.sarh.cycle.model.Triangle;
import org.sarh.cycle.model.TriangleLeg;
import org.sarh.cycle.ws.OrderBookCache;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Recomputes every triangle touched by a book update and reports any whose net-of-fees profit
 * clears the configured threshold.
 *
 * <p>The arithmetic per leg, with {@code c = fee + slippage} for that leg's fee schedule:
 * <pre>
 *   BUY  on market (src/dst) at ask:   out[src] = in[dst] / ask * (1 - c)
 *   SELL on market (src/dst) at bid:   out[dst] = in[src] * bid * (1 - c)
 * </pre>
 * Nobitex charges the fee on the asset you receive, so the haircut is applied to the output of
 * every leg. The three legs compose, and the loop's net ratio is {@code out3 / in1 - 1}.
 *
 * <p>Sizing is top-of-book only: each leg is valued at the current best bid/ask, with no check
 * that the visible depth actually covers {@code trade_notional_irt}. On a thin leg the real fill
 * price is worse than this estimate -- see README "Known limitations" -- which is exactly what
 * {@code cross_by} and {@code max_leg_slippage} in the executor are backstops for.
 */
public final class ArbitrageDetector {

    private final AppConfig cfg;
    private final OrderBookCache books;
    private final Map<String, List<Triangle>> bySymbol = new HashMap<>();
    private final Consumer<Opportunity> onOpportunity;
    /** Latest and best-ever net ratio per triangle id, for the near-miss report. */
    private final Map<String, double[]> ratios = new ConcurrentHashMap<>();
    private final AtomicLong evaluations = new AtomicLong();

    public ArbitrageDetector(AppConfig cfg, OrderBookCache books, List<Triangle> triangles,
                              Consumer<Opportunity> onOpportunity) {
        this.cfg = cfg;
        this.books = books;
        this.onOpportunity = onOpportunity;
        for (Triangle t : triangles) {
            for (String symbol : t.symbols()) {
                bySymbol.computeIfAbsent(symbol, k -> new ArrayList<>()).add(t);
            }
        }
    }

    /** Call whenever {@code symbol}'s top of book changes. Runs on the websocket thread: keep it cheap. */
    public void onBookUpdate(String symbol) {
        List<Triangle> affected = bySymbol.get(symbol);
        if (affected == null) return;
        for (Triangle t : affected) {
            evaluate(t).ifPresent(onOpportunity);
        }
    }

    Optional<Opportunity> evaluate(Triangle t) {
        long now = System.currentTimeMillis();
        OrderBookTop[] tops = new OrderBookTop[3];
        for (int i = 0; i < 3; i++) {
            OrderBookTop top = books.get(t.legs.get(i).market.symbol);
            if (top == null || top.isStale(now, cfg.maxBookAgeMillis) || !top.isSane()) {
                return Optional.empty();
            }
            tops[i] = top;
        }

        double start = cfg.tradeNotionalIrt;
        double amount = start;
        double[] legPrices = new double[3];
        double[] legAmountsOut = new double[3];

        for (int i = 0; i < 3; i++) {
            TriangleLeg leg = t.legs.get(i);
            double price = leg.price(tops[i]);
            legPrices[i] = price;
            double cost = (leg.isRialQuoted() ? cfg.takerFeeIrt : cfg.takerFeeUsdt) + cfg.slippage;
            amount = leg.side == Side.BUY ? (amount / price) * (1 - cost) : (amount * price) * (1 - cost);
            legAmountsOut[i] = amount;
        }

        double netProfitRatio = amount / start - 1;
        evaluations.incrementAndGet();
        ratios.compute(t.id(), (k, v) -> {
            if (v == null) return new double[]{netProfitRatio, netProfitRatio};
            v[0] = netProfitRatio;
            v[1] = Math.max(v[1], netProfitRatio);
            return v;
        });
        if (netProfitRatio < cfg.minProfitRatio) return Optional.empty();

        // The middle leg is USDT-quoted in both directions; make sure it is not below Nobitex's
        // USDT minimum, or leg 1 would fill and leg 2 would be rejected -- the worst outcome.
        if (usdtNotionalOfMiddleLeg(t, legPrices, legAmountsOut) < cfg.minOrderUsdt) {
            return Optional.empty();
        }

        return Optional.of(new Opportunity(t, start, legPrices, legAmountsOut, netProfitRatio, Instant.now()));
    }

    /**
     * Human-readable snapshot of the closest loops to profitability: the top {@code n} by best
     * ratio ever seen, with their current ratio. Negative numbers are normal -- they are the cost
     * of three legs of fees and spread that the price dislocation did not cover.
     */
    public String nearMissReport(int n) {
        if (ratios.isEmpty()) return "near-miss: no triangles evaluated yet";
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "near-miss report (%,d evaluations, threshold %+.3f%%) -- best ever / current:%n",
                evaluations.get(), cfg.minProfitRatio * 100));
        ratios.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[1], a.getValue()[1]))
                .limit(n)
                .forEach(e -> sb.append(String.format(Locale.ROOT, "  %-18s best %+.3f%%   now %+.3f%%%n",
                        e.getKey(), e.getValue()[1] * 100, e.getValue()[0] * 100)));
        return sb.toString().stripTrailing();
    }

    /** USDT value of the second leg: what it spends (REVERSE: buying BASE with USDT) or what it
     *  yields (FORWARD: selling BASE for USDT). */
    private static double usdtNotionalOfMiddleLeg(Triangle t, double[] prices, double[] outs) {
        TriangleLeg mid = t.legs.get(1);
        double in = outs[0]; // input of leg 2 is output of leg 1
        return mid.side == Side.BUY ? in : in * prices[1];
    }
}
