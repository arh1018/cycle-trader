package org.sarh.cycle.model;

import java.time.Instant;
import java.util.List;

/**
 * One reportable event with a realised rial P&amp;L: a cycle (including any later unwind of a
 * stranded coin), a USDTIRT rebalance, or a startup sweep of a stray coin balance.
 *
 * <p>Valuation is conservative and explicit: anything <em>spent</em> in USDT is valued at the
 * USDTIRT ask (what it costs to replace), anything <em>held or received</em> in USDT at the bid
 * (what it fetches). The rates used are stored on the row so every number can be re-derived.
 */
public final class TradeEpisode {
    public enum Kind {
        CYCLE,
        REBALANCE,
        SWEEP
    }

    public enum Status {
        /** Both legs produced output; cycle closed. */
        FILLED,
        /** Leg 2 failed; the stranded coin was sold back to the start currency. */
        ABORTED_RECOVERED,
        /** Leg 2 failed; only part of the stranded coin could be unwound. Manual attention. */
        ABORTED_PARTIAL_RECOVERY,
        /** Leg 2 failed; nothing could be unwound. Manual attention. */
        ABORTED_UNRECOVERED,
        /** Leg 1 never matched; nothing was spent. */
        ABORTED_NOTHING_SPENT,
        /** A USDTIRT conversion between the two quote piles. */
        REBALANCED,
        REBALANCE_FAILED,
        /** A stray coin balance found at startup and sold to rial. */
        SWEPT,
        SWEEP_FAILED
    }

    public final String id;
    public final String mode;
    public final Kind kind;
    public final String pathLabel; // cycle id, or "REBALANCE IRT>USDT", or "SWEEP HOME"
    public final String path; // e.g. IRT>BTC>USDT
    public final Instant startedAt;
    public final Instant finishedAt;
    public final Status status;
    public final double expectedProfitRatio; // 0 for rebalance/sweep
    public final String startCurrency;
    public final double startAmount; // spent
    public final String endCurrency;
    public final double endAmount; // received by the primary legs
    public final double recoveredAmount; // received by recovery legs, in startCurrency
    public final double usdtIrtBid, usdtIrtAsk;
    public final List<LegResult> legs;
    public final List<LegResult> recoveryLegs;
    public final String note;

    public TradeEpisode(
            String id,
            String mode,
            Kind kind,
            String pathLabel,
            String path,
            Instant startedAt,
            Instant finishedAt,
            Status status,
            double expectedProfitRatio,
            String startCurrency,
            double startAmount,
            String endCurrency,
            double endAmount,
            double recoveredAmount,
            double usdtIrtBid,
            double usdtIrtAsk,
            List<LegResult> legs,
            List<LegResult> recoveryLegs,
            String note) {
        this.id = id;
        this.mode = mode;
        this.kind = kind;
        this.pathLabel = pathLabel;
        this.path = path;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.status = status;
        this.expectedProfitRatio = expectedProfitRatio;
        this.startCurrency = startCurrency;
        this.startAmount = startAmount;
        this.endCurrency = endCurrency;
        this.endAmount = endAmount;
        this.recoveredAmount = recoveredAmount;
        this.usdtIrtBid = usdtIrtBid;
        this.usdtIrtAsk = usdtIrtAsk;
        this.legs = List.copyOf(legs);
        this.recoveryLegs = List.copyOf(recoveryLegs);
        this.note = note == null ? "" : note;
    }

    /** Rial value of an amount spent (USDT at ask). */
    public double spentValueIrt() {
        return toRial(startCurrency, startAmount, usdtIrtAsk);
    }

    /** Rial value of everything that came back: primary output plus recovery, USDT at bid. */
    public double backValueIrt() {
        return toRial(endCurrency, endAmount, usdtIrtBid)
                + toRial(startCurrency, recoveredAmount, usdtIrtBid);
    }

    public double pnlIrt() {
        return backValueIrt() - spentValueIrt();
    }

    public double pnlRatio() {
        double spent = spentValueIrt();
        return spent > 0 ? pnlIrt() / spent : 0;
    }

    private static double toRial(String currency, double amount, double usdtRate) {
        if (amount == 0) {
            return 0;
        }
        if (Cycle.RIAL.equals(currency)) {
            return amount;
        }
        if (Cycle.USDT.equals(currency)) {
            return amount * usdtRate;
        }
        return 0; // a stranded coin is not valued; it shows up when it is recovered
    }

    @Override
    public String toString() {
        return String.format(
                "%s %s %s spent=%.0f IRT back=%.0f IRT pnl=%+.0f IRT (%+.3f%%, expected %+.3f%%)",
                id,
                path,
                status,
                spentValueIrt(),
                backValueIrt(),
                pnlIrt(),
                pnlRatio() * 100,
                expectedProfitRatio * 100);
    }
}
