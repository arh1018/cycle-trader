package org.sarh.cycle.model;

import java.time.Instant;

/** A detected profitable loop, sized against current top-of-book prices. */
public final class Opportunity {
    public final Triangle triangle;
    public final double startAmountIrt;
    public final double[] legPrices;     // price used to value each leg, in order
    public final double[] legAmountsOut; // amount received after each leg
    public final double netProfitRatio;  // (final IRT / start IRT) - 1, after fees + slippage
    public final Instant detectedAt;

    public Opportunity(Triangle triangle, double startAmountIrt, double[] legPrices,
                        double[] legAmountsOut, double netProfitRatio, Instant detectedAt) {
        this.triangle = triangle;
        this.startAmountIrt = startAmountIrt;
        this.legPrices = legPrices;
        this.legAmountsOut = legAmountsOut;
        this.netProfitRatio = netProfitRatio;
        this.detectedAt = detectedAt;
    }

    public double finalAmountIrt() {
        return legAmountsOut[legAmountsOut.length - 1];
    }

    public double profitIrt() {
        return finalAmountIrt() - startAmountIrt;
    }

    @Override
    public String toString() {
        return String.format(
                "%s start=%.0f IRT final=%.0f IRT profit=%.3f%% (%.0f IRT)",
                triangle.id(), startAmountIrt, finalAmountIrt(), netProfitRatio * 100, profitIrt());
    }
}
