package org.sarh.cycle.model;

import java.time.Instant;

/**
 * A cycle priced against the live book depth for a concrete size.
 *
 * <p>Both directions are sized to the same rial notional. The start pile is valued at what it would
 * cost to acquire (USDT at the USDTIRT ask), the end pile at what it would fetch (USDT at the
 * USDTIRT bid), so {@code netProfitRatio} is a conservative, realisable number.
 */
public final class Opportunity {
    public final Cycle cycle;
    public final double startAmount; // in cycle.startCurrency()
    public final double startValueIrt;
    public final double[] legVwap; // effective price of each leg for its size
    public final double[] legMarginal; // deepest level each leg must reach
    public final double[] legAmountsOut; // net output of each leg
    public final double endAmount; // in cycle.endCurrency()
    public final double endValueIrt;
    public final double usdtIrtBid, usdtIrtAsk; // reference rates used for valuation
    public final double netProfitRatio;
    public final Instant detectedAt;

    public Opportunity(
            Cycle cycle,
            double startAmount,
            double startValueIrt,
            double[] legVwap,
            double[] legMarginal,
            double[] legAmountsOut,
            double endAmount,
            double endValueIrt,
            double usdtIrtBid,
            double usdtIrtAsk,
            Instant detectedAt) {
        this.cycle = cycle;
        this.startAmount = startAmount;
        this.startValueIrt = startValueIrt;
        this.legVwap = legVwap;
        this.legMarginal = legMarginal;
        this.legAmountsOut = legAmountsOut;
        this.endAmount = endAmount;
        this.endValueIrt = endValueIrt;
        this.usdtIrtBid = usdtIrtBid;
        this.usdtIrtAsk = usdtIrtAsk;
        this.netProfitRatio = endValueIrt / startValueIrt - 1;
        this.detectedAt = detectedAt;
    }

    public double expectedProfitIrt() {
        return endValueIrt - startValueIrt;
    }

    @Override
    public String toString() {
        return String.format(
                "%s start=%.4f %s (%.0f IRT) end=%.4f %s (%.0f IRT) profit=%+.3f%% (%.0f IRT)",
                cycle.id(),
                startAmount,
                Cycle.label(cycle.startCurrency()),
                startValueIrt,
                endAmount,
                Cycle.label(cycle.endCurrency()),
                endValueIrt,
                netProfitRatio * 100,
                expectedProfitIrt());
    }
}
