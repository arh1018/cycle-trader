package org.sarh.cycle.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TriangleExecutionResult {
    public final Opportunity opportunity;
    public final List<LegResult> legs;

    public TriangleExecutionResult(Opportunity opportunity, List<LegResult> legs) {
        this.opportunity = opportunity;
        this.legs = List.copyOf(legs);
    }

    /** True only when all three legs filled completely. */
    public boolean fullyFilled() {
        return legs.size() == 3 && legs.stream().allMatch(LegResult::completed);
    }

    /** Rial actually spent by the first leg (its consumed input). */
    public double irtSpent() {
        return legs.isEmpty() ? 0 : legs.get(0).amountConsumed;
    }

    /** Rial produced by the last leg, if it ran at all. */
    public double irtReceived() {
        if (legs.size() < 3) return 0;
        LegResult last = legs.get(2);
        return Triangle.RIAL.equals(last.leg.outputCurrency()) ? last.amountOut : 0;
    }

    /**
     * Non-rial inventory the loop left behind, by currency. Empty when the triangle closed cleanly.
     *
     * <p>Each leg is recorded with {@code amountIn} equal to the previous leg's {@code amountOut}
     * (skipped legs included), so a leg's unconverted input IS the previous leg's stranded output --
     * counting leftover input once per leg covers everything without double counting. The only
     * other case is a trailing leg whose non-rial output has no recorded successor.
     */
    public Map<String, Double> strandedInventory() {
        Map<String, Double> stranded = new LinkedHashMap<>();
        for (LegResult r : legs) {
            String in = r.leg.inputCurrency();
            if (!Triangle.RIAL.equals(in) && r.leftoverInput() > 0) {
                stranded.merge(in, r.leftoverInput(), Double::sum);
            }
        }
        if (!legs.isEmpty()) {
            LegResult last = legs.get(legs.size() - 1);
            String out = last.leg.outputCurrency();
            if (!Triangle.RIAL.equals(out) && last.producedAnything()) {
                stranded.merge(out, last.amountOut, Double::sum);
            }
        }
        return stranded;
    }
}
