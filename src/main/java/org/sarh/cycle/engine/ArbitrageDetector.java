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
import java.util.Map;
import java.util.Optional;
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
        if (netProfitRatio < cfg.minProfitRatio) return Optional.empty();

        // The middle leg is USDT-quoted in both directions; make sure it is not below Nobitex's
        // USDT minimum, or leg 1 would fill and leg 2 would be rejected -- the worst outcome.
        if (usdtNotionalOfMiddleLeg(t, legPrices, legAmountsOut) < cfg.minOrderUsdt) {
            return Optional.empty();
        }

        return Optional.of(new Opportunity(t, start, legPrices, legAmountsOut, netProfitRatio, Instant.now()));
    }

    /** USDT value of the second leg: what it spends (REVERSE: buying BASE with USDT) or what it
     *  yields (FORWARD: selling BASE for USDT). */
    private static double usdtNotionalOfMiddleLeg(Triangle t, double[] prices, double[] outs) {
        TriangleLeg mid = t.legs.get(1);
        double in = outs[0]; // input of leg 2 is output of leg 1
        return mid.side == Side.BUY ? in : in * prices[1];
    }
}
