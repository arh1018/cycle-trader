package org.sarh.cycle.model;

/**
 * Best bid/ask for one Nobitex market. Populated straight from the
 * {@code public:orderbook-*} websocket channel, which is always RIAL for
 * *IRT markets and plain USDT for *USDT markets -- neither is toman, so no
 * unit conversion happens here.
 */
public final class OrderBookTop {
    public final String symbol;
    public final double bestBid;
    public final double bestAsk;
    /** Decimal places Nobitex itself printed for the touch prices; used to round limit prices. */
    public final int priceDecimals;
    /** Local wall-clock time the update was received. Used for staleness so a skewed exchange
     *  clock cannot make a live feed look dead (or a dead one look live). */
    public final long receivedMillis;

    public OrderBookTop(String symbol, double bestBid, double bestAsk, int priceDecimals, long receivedMillis) {
        this.symbol = symbol;
        this.bestBid = bestBid;
        this.bestAsk = bestAsk;
        this.priceDecimals = priceDecimals;
        this.receivedMillis = receivedMillis;
    }

    public boolean isStale(long nowMillis, long maxAgeMillis) {
        return nowMillis - receivedMillis > maxAgeMillis;
    }

    /** A crossed or locked book (bid &gt;= ask) is a transient artefact, not an opportunity. */
    public boolean isSane() {
        return bestBid > 0 && bestAsk > 0 && bestBid < bestAsk;
    }

    @Override
    public String toString() {
        return symbol + "[bid=" + bestBid + " ask=" + bestAsk + "]";
    }
}
