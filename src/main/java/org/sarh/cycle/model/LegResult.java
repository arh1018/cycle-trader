package org.sarh.cycle.model;

/**
 * Outcome of clearing one leg, from either the paper or the live executor.
 *
 * <p>Amounts are net of exchange fees, in the leg's own currencies:
 * {@code amountIn} is what the leg was asked to convert, {@code amountConsumed} is how much of that
 * was actually matched (less than {@code amountIn} on a partial fill), and {@code amountOut} is
 * what landed in the wallet as a result. {@code amountIn - amountConsumed} is therefore inventory
 * left behind in the input currency, which matters for unwinding an aborted triangle.
 */
public final class LegResult {
    public enum Status { FILLED, PARTIAL, FAILED, SKIPPED }

    public final TriangleLeg leg;
    public final Status status;
    public final double amountIn;
    public final double amountConsumed;
    public final double amountOut;
    public final double avgPrice;
    public final Long exchangeOrderId; // null in paper mode
    public final String note;

    private LegResult(TriangleLeg leg, Status status, double amountIn, double amountConsumed,
                       double amountOut, double avgPrice, Long exchangeOrderId, String note) {
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
     * @param orderedSrc  src-currency amount actually sent to the exchange (after step rounding)
     * @param matchedSrc  src-currency amount the exchange reports as matched
     *
     * FILLED vs PARTIAL is decided on what was ordered, not on {@code amountIn}: the step-rounding
     * remainder between the two is genuine leftover input (and is accounted for as such), but a
     * fully matched order is not a partial fill.
     */
    public static LegResult filled(TriangleLeg leg, double amountIn, double orderedSrc, double matchedSrc,
                                    double amountConsumed, double amountOut, double avgPrice, Long exchangeOrderId) {
        boolean complete = matchedSrc >= orderedSrc * (1 - 1e-9);
        return new LegResult(leg, complete ? Status.FILLED : Status.PARTIAL, amountIn, amountConsumed, amountOut,
                avgPrice, exchangeOrderId, "");
    }

    public static LegResult failed(TriangleLeg leg, double amountIn, String note) {
        return new LegResult(leg, Status.FAILED, amountIn, 0, 0, 0, null, note);
    }

    public static LegResult skipped(TriangleLeg leg, double amountIn, String note) {
        return new LegResult(leg, Status.SKIPPED, amountIn, 0, 0, 0, null, note);
    }

    public boolean completed() {
        return status == Status.FILLED;
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
            case FILLED -> String.format("%s FILLED in=%.8f out=%.8f @%.6f", leg, amountIn, amountOut, avgPrice);
            case PARTIAL -> String.format("%s PARTIAL in=%.8f consumed=%.8f out=%.8f @%.6f", leg, amountIn,
                    amountConsumed, amountOut, avgPrice);
            default -> String.format("%s %s (%s)", leg, status, note);
        };
    }
}
