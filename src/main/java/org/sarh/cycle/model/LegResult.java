package org.sarh.cycle.model;

/**
 * Outcome of clearing one leg, from either the paper or the live executor.
 *
 * <p>Amounts are net of exchange fees, in the leg's own currencies: {@code amountIn} is what the
 * leg was asked to convert, {@code amountConsumed} how much of that was actually matched, and
 * {@code amountOut} what landed in the wallet. {@code amountIn - amountConsumed} is inventory left
 * behind in the input currency.
 *
 * <p>Nobitex closes an order as {@code Done} once its unfilled remainder is below the market's
 * minimum, so {@code matched < ordered} on a completed order is normal. A leg is therefore {@code
 * FILLED} when it matched essentially all of what was ordered, {@code PARTIAL} when it produced
 * something but clearly less, and either way the cycle continues with what it got.
 */
public final class LegResult {
    public enum Status {
        FILLED,
        PARTIAL,
        FAILED,
        SKIPPED
    }

    /** Matched / ordered ratio at or above which a fill counts as complete. */
    public static final double FILL_TOLERANCE = 0.995;

    public final CycleLeg leg;
    public final Status status;
    public final double amountIn;
    public final double amountConsumed;
    public final double amountOut;
    public final double avgPrice;
    public final Long exchangeOrderId; // null in paper mode
    public final String note;

    private LegResult(
            CycleLeg leg,
            Status status,
            double amountIn,
            double amountConsumed,
            double amountOut,
            double avgPrice,
            Long exchangeOrderId,
            String note) {
        this.leg = leg;
        this.status = status;
        this.amountIn = amountIn;
        this.amountConsumed = amountConsumed;
        this.amountOut = amountOut;
        this.avgPrice = avgPrice;
        this.exchangeOrderId = exchangeOrderId;
        this.note = note;
    }

    /**
     * @param orderedSrc src amount actually sent to the exchange (after step rounding)
     * @param matchedSrc src amount the exchange reports as matched
     */
    public static LegResult filled(
            CycleLeg leg,
            double amountIn,
            double orderedSrc,
            double matchedSrc,
            double amountConsumed,
            double amountOut,
            double avgPrice,
            Long exchangeOrderId) {
        Status s = matchedSrc >= orderedSrc * FILL_TOLERANCE ? Status.FILLED : Status.PARTIAL;
        return new LegResult(
                leg, s, amountIn, amountConsumed, amountOut, avgPrice, exchangeOrderId, "");
    }

    public static LegResult failed(CycleLeg leg, double amountIn, String note) {
        return new LegResult(leg, Status.FAILED, amountIn, 0, 0, 0, null, note);
    }

    public static LegResult skipped(CycleLeg leg, double amountIn, String note) {
        return new LegResult(leg, Status.SKIPPED, amountIn, 0, 0, 0, null, note);
    }

    public boolean producedAnything() {
        return amountOut > 0;
    }

    public double leftoverInput() {
        return Math.max(0, amountIn - amountConsumed);
    }

    @Override
    public String toString() {
        return switch (status) {
            case FILLED ->
                    String.format(
                            "%s FILLED in=%.8f out=%.8f @%.8f", leg, amountIn, amountOut, avgPrice);
            case PARTIAL ->
                    String.format(
                            "%s PARTIAL in=%.8f consumed=%.8f out=%.8f @%.8f",
                            leg, amountIn, amountConsumed, amountOut, avgPrice);
            default -> String.format("%s %s (%s)", leg, status, note);
        };
    }
}
