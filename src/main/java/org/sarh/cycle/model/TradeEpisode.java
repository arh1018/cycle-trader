package org.sarh.cycle.model;

import java.time.Instant;
import java.util.List;

/**
 * One complete attempt at a triangle, from the first order to the point where the account is back
 * in rial -- either because the loop closed, or because stranded inventory was unwound afterwards.
 * This is the unit the P&amp;L report is written in.
 */
public final class TradeEpisode {
    public enum Status {
        /** All three legs filled; loop closed. */
        FILLED,
        /** Loop aborted; every stranded asset was sold back to rial. */
        ABORTED_RECOVERED,
        /** Loop aborted; unwind only partially succeeded. Manual attention needed. */
        ABORTED_PARTIAL_RECOVERY,
        /** Loop aborted; nothing could be unwound. Manual attention needed. */
        ABORTED_UNRECOVERED,
        /** Loop aborted before anything was spent; nothing to recover. */
        ABORTED_NOTHING_SPENT
    }

    public final String id;
    public final String mode;
    public final Triangle triangle;
    public final Instant startedAt;
    public final Instant finishedAt;
    public final Status status;
    public final double expectedProfitRatio;
    public final double irtSpent;
    public final double irtReceived;      // from the loop's own last leg
    public final double irtRecovered;     // from unwinding stranded inventory
    public final List<LegResult> legs;
    public final List<LegResult> recoveryLegs;
    public final String note;

    public TradeEpisode(String id, String mode, Triangle triangle, Instant startedAt, Instant finishedAt,
                         Status status, double expectedProfitRatio, double irtSpent, double irtReceived,
                         double irtRecovered, List<LegResult> legs, List<LegResult> recoveryLegs, String note) {
        this.id = id;
        this.mode = mode;
        this.triangle = triangle;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.status = status;
        this.expectedProfitRatio = expectedProfitRatio;
        this.irtSpent = irtSpent;
        this.irtReceived = irtReceived;
        this.irtRecovered = irtRecovered;
        this.legs = List.copyOf(legs);
        this.recoveryLegs = List.copyOf(recoveryLegs);
        this.note = note == null ? "" : note;
    }

    public double irtBack() {
        return irtReceived + irtRecovered;
    }

    public double pnlIrt() {
        return irtBack() - irtSpent;
    }

    public double pnlRatio() {
        return irtSpent > 0 ? pnlIrt() / irtSpent : 0;
    }

    @Override
    public String toString() {
        return String.format("%s %s %s spent=%.0f back=%.0f pnl=%.0f IRT (%.3f%%, expected %.3f%%)",
                id, triangle.path(), status, irtSpent, irtBack(), pnlIrt(), pnlRatio() * 100,
                expectedProfitRatio * 100);
    }
}
