package org.sarh.cycle.model;

/**
 * Depth snapshot of one Nobitex market, best level first. Prices are in the market's dst unit (rial
 * for *IRT, USDT for *USDT); sizes are in src units.
 *
 * <p>Everything the engine prices is a <em>walk</em> of this book for a concrete size, never the
 * touch alone: the first live loss came from a top bid with almost no size behind it.
 */
public final class OrderBook {
    public final String symbol;
    public final double[] bidPx, bidSz; // descending price
    public final double[] askPx, askSz; // ascending price

    /** Decimal places Nobitex itself printed for prices / sizes; used to round orders. */
    public final int priceDecimals;

    public final int sizeDecimals;

    /** Local wall-clock receive time, so a skewed exchange clock cannot fake freshness. */
    public final long receivedMillis;

    public OrderBook(
            String symbol,
            double[] bidPx,
            double[] bidSz,
            double[] askPx,
            double[] askSz,
            int priceDecimals,
            int sizeDecimals,
            long receivedMillis) {
        this.symbol = symbol;
        this.bidPx = bidPx;
        this.bidSz = bidSz;
        this.askPx = askPx;
        this.askSz = askSz;
        this.priceDecimals = priceDecimals;
        this.sizeDecimals = sizeDecimals;
        this.receivedMillis = receivedMillis;
    }

    public double bestBid() {
        return bidPx.length == 0 ? 0 : bidPx[0];
    }

    public double bestAsk() {
        return askPx.length == 0 ? 0 : askPx[0];
    }

    public boolean isStale(long nowMillis, long maxAgeMillis) {
        return nowMillis - receivedMillis > maxAgeMillis;
    }

    /** A crossed or locked book (bid &gt;= ask) is a transient artefact, not an opportunity. */
    public boolean isSane() {
        return bestBid() > 0 && bestAsk() > 0 && bestBid() < bestAsk();
    }

    /**
     * Result of walking one side of the book.
     *
     * @param srcAmount src units bought or sold
     * @param dstAmount dst units spent (buy) or received (sell), before fees
     * @param vwap dstAmount / srcAmount
     * @param marginal price of the last level touched -- what a limit order must reach
     * @param complete false if the visible depth ran out before the request was satisfied
     */
    public record Walk(
            double srcAmount, double dstAmount, double vwap, double marginal, boolean complete) {
        public static final Walk EMPTY = new Walk(0, 0, 0, 0, false);
    }

    /** Buy src by spending up to {@code dstBudget}, walking the asks from the best. */
    public Walk buyWithBudget(double dstBudget) {
        double remaining = dstBudget, src = 0, dst = 0, marginal = 0;
        for (int i = 0; i < askPx.length && remaining > 0; i++) {
            double px = askPx[i], levelDst = askSz[i] * px;
            double take = Math.min(levelDst, remaining);
            src += take / px;
            dst += take;
            remaining -= take;
            marginal = px;
        }
        if (src <= 0) {
            return Walk.EMPTY;
        }
        return new Walk(src, dst, dst / src, marginal, remaining <= dstBudget * 1e-9);
    }

    /** Sell {@code srcAmount} of src, walking the bids from the best. */
    public Walk sellAmount(double srcAmount) {
        double remaining = srcAmount, src = 0, dst = 0, marginal = 0;
        for (int i = 0; i < bidPx.length && remaining > 0; i++) {
            double take = Math.min(bidSz[i], remaining);
            src += take;
            dst += take * bidPx[i];
            remaining -= take;
            marginal = bidPx[i];
        }
        if (src <= 0) {
            return Walk.EMPTY;
        }
        return new Walk(src, dst, dst / src, marginal, remaining <= srcAmount * 1e-9);
    }

    @Override
    public String toString() {
        return symbol
                + "[bid="
                + bestBid()
                + " ask="
                + bestAsk()
                + " levels="
                + bidPx.length
                + "/"
                + askPx.length
                + "]";
    }
}
