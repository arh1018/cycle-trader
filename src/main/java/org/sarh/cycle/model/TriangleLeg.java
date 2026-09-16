package org.sarh.cycle.model;

/** One of the three legs of a triangle: which market, and which side to trade on it. */
public final class TriangleLeg {
    public final Market market;
    public final Side side;

    public TriangleLeg(Market market, Side side) {
        this.market = market;
        this.side = side;
    }

    /** Price used to value this leg: the ask when buying, the bid when selling. */
    public double price(OrderBookTop top) {
        return side == Side.BUY ? top.bestAsk : top.bestBid;
    }

    /** Currency this leg consumes. Buying spends the quote (dst); selling spends the base (src). */
    public String inputCurrency() {
        return side == Side.BUY ? market.dst : market.src;
    }

    /** Currency this leg produces. Buying yields the base (src); selling yields the quote (dst). */
    public String outputCurrency() {
        return side == Side.BUY ? market.src : market.dst;
    }

    /** Fee rate class: rial-quoted legs and USDT-quoted legs are on different fee schedules. */
    public boolean isRialQuoted() {
        return market.isRial();
    }

    @Override
    public String toString() {
        return side + " " + market.symbol;
    }
}
