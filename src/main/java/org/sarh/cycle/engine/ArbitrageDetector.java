package org.sarh.cycle.engine;

import org.sarh.cycle.config.AppConfig;
import org.sarh.cycle.model.Cycle;
import org.sarh.cycle.model.CycleLeg;
import org.sarh.cycle.model.Opportunity;
import org.sarh.cycle.model.OrderBook;
import org.sarh.cycle.model.Side;
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
 * Re-prices every cycle touched by a book update against the live depth and reports any whose
 * conservative net profit clears the threshold.
 *
 * <p>Per leg, with {@code c = fee + slippage} for that leg's fee schedule, the book is
 * <em>walked</em> for the leg's real size:
 *
 * <pre>
 *   BUY  : spend in[dst] down the asks  -&gt; out[src] = walked src × (1 − c)
 *   SELL : sell  in[src] down the bids  -&gt; out[dst] = walked dst × (1 − c)
 * </pre>
 *
 * If the visible depth cannot absorb the size, the cycle is skipped rather than priced at the touch
 * -- the first live loss came from exactly that.
 *
 * <p>Valuation: IRT&gt;COIN&gt;USDT ends in USDT, valued at the USDTIRT <em>bid</em>;
 * USDT&gt;COIN&gt;IRT starts from USDT, valued at the USDTIRT <em>ask</em>. Both directions are
 * sized to the same rial notional.
 */
public final class ArbitrageDetector {

    private final AppConfig cfg;
    private final OrderBookCache books;
    private final String usdtIrtSymbol;
    private final Map<String, List<Cycle>> bySymbol = new HashMap<>();
    private final Consumer<Opportunity> onOpportunity;

    /** Latest and best-ever net ratio per cycle id, for the near-miss report. */
    private final Map<String, double[]> ratios = new ConcurrentHashMap<>();

    private final AtomicLong evaluations = new AtomicLong();

    public ArbitrageDetector(
            AppConfig cfg,
            OrderBookCache books,
            String usdtIrtSymbol,
            List<Cycle> cycles,
            Consumer<Opportunity> onOpportunity) {
        this.cfg = cfg;
        this.books = books;
        this.usdtIrtSymbol = usdtIrtSymbol;
        this.onOpportunity = onOpportunity;
        for (Cycle c : cycles) {
            for (String symbol : c.symbols()) {
                bySymbol.computeIfAbsent(symbol, k -> new ArrayList<>()).add(c);
            }
        }
        // Every cycle is valued through USDTIRT, so a USDTIRT tick re-prices all of them.
        bySymbol.put(usdtIrtSymbol, new ArrayList<>(cycles));
    }

    /** Call whenever {@code symbol}'s book changes. Runs on the websocket thread: keep it cheap. */
    public void onBookUpdate(String symbol) {
        List<Cycle> affected = bySymbol.get(symbol);
        if (affected == null) {
            return;
        }
        for (Cycle c : affected) {
            evaluate(c).ifPresent(onOpportunity);
        }
    }

    Optional<Opportunity> evaluate(Cycle c) {
        long now = System.currentTimeMillis();
        OrderBook fx = fresh(usdtIrtSymbol, now);
        OrderBook b0 = fresh(c.legs.get(0).market.symbol, now);
        OrderBook b1 = fresh(c.legs.get(1).market.symbol, now);
        if (fx == null || b0 == null || b1 == null) {
            return Optional.empty();
        }

        double notional = cfg.tradeNotionalIrt;
        double startAmount, startValueIrt;
        if (c.direction == Cycle.Direction.IRT_TO_USDT) {
            startAmount = notional;
            startValueIrt = notional;
        } else {
            startAmount = notional / fx.bestAsk();
            startValueIrt = notional;
        }

        double[] vwap = new double[2], marginal = new double[2], outs = new double[2];
        double amount = startAmount;
        OrderBook[] legBooks = {b0, b1};
        for (int i = 0; i < 2; i++) {
            CycleLeg leg = c.legs.get(i);
            OrderBook.Walk w = leg.walk(legBooks[i], amount);
            if (!w.complete()) return Optional.empty(); // not enough visible depth for this size
            double cost = (leg.isRialQuoted() ? cfg.takerFeeIrt : cfg.takerFeeUsdt) + cfg.slippage;
            double gross = leg.side == Side.BUY ? w.srcAmount() : w.dstAmount();
            amount = gross * (1 - cost);
            vwap[i] = w.vwap();
            marginal[i] = w.marginal();
            outs[i] = amount;
        }

        double endAmount = amount;
        double endValueIrt =
                c.direction == Cycle.Direction.IRT_TO_USDT ? endAmount * fx.bestBid() : endAmount;
        double ratio = endValueIrt / startValueIrt - 1;

        evaluations.incrementAndGet();
        ratios.compute(
                c.id(),
                (k, v) -> {
                    if (v == null) {
                        return new double[] {ratio, ratio};
                    }
                    v[0] = ratio;
                    v[1] = Math.max(v[1], ratio);
                    return v;
                });
        if (ratio < cfg.minProfitRatio) {
            return Optional.empty();
        }

        // Exchange minimums per leg, in each leg's dst unit.
        double leg1DstNotional =
                c.legs.get(0).side == Side.BUY ? startAmount : startAmount * vwap[0];
        double leg2DstNotional = c.legs.get(1).side == Side.BUY ? outs[0] : outs[0] * vwap[1];
        if (!clearsMinimum(c.legs.get(0), leg1DstNotional)
                || !clearsMinimum(c.legs.get(1), leg2DstNotional)) {
            return Optional.empty();
        }

        return Optional.of(
                new Opportunity(
                        c,
                        startAmount,
                        startValueIrt,
                        vwap,
                        marginal,
                        outs,
                        endAmount,
                        endValueIrt,
                        fx.bestBid(),
                        fx.bestAsk(),
                        Instant.now()));
    }

    private boolean clearsMinimum(CycleLeg leg, double dstNotional) {
        return dstNotional >= (leg.isRialQuoted() ? cfg.minOrderRial : cfg.minOrderUsdt);
    }

    private OrderBook fresh(String symbol, long now) {
        OrderBook b = books.get(symbol);
        if (b == null || b.isStale(now, cfg.maxBookAgeMillis) || !b.isSane()) {
            return null;
        }
        return b;
    }

    /**
     * The closest cycles to profitability: top {@code n} by best ratio ever seen, with the current
     * ratio. Negative numbers are normal -- two legs of fees and spread the dislocation did not
     * cover.
     */
    public String nearMissReport(int n) {
        if (ratios.isEmpty()) {
            return "near-miss: no cycles evaluated yet";
        }
        StringBuilder sb =
                new StringBuilder(
                        String.format(
                                Locale.ROOT,
                                "near-miss report (%,d evaluations, threshold %+.3f%%) -- best ever"
                                        + " / current:%n",
                                evaluations.get(),
                                cfg.minProfitRatio * 100));
        ratios.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[1], a.getValue()[1]))
                .limit(n)
                .forEach(
                        e ->
                                sb.append(
                                        String.format(
                                                Locale.ROOT,
                                                "  %-18s best %+.3f%%   now %+.3f%%%n",
                                                e.getKey(),
                                                e.getValue()[1] * 100,
                                                e.getValue()[0] * 100)));
        return sb.toString().stripTrailing();
    }
}
