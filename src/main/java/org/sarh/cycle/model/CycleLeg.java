package org.sarh.cycle.model;

/** One leg of a cycle: which market, and which side to trade on it. */
public final class CycleLeg {
    public final Market market;
    public final Side side;

    public CycleLeg(Market market, Side side) {
        this.market = market;
        this.side = side;
    }

    /** Currency this leg consumes. Buying spends the quote (dst); selling spends the base (src). */
    public String inputCurrency() {
        return side == Side.BUY ? market.dst : market.src;
    }

    /** Currency this leg produces. Buying yields the base (src); selling yields the quote (dst). */
    public String outputCurrency() {
        return side == Side.BUY ? market.src : market.dst;
    }

    /** Fee schedule: rial-quoted and USDT-quoted markets differ. */
    public boolean isRialQuoted() {
        return market.isRial();
    }

    /**
     * Walk the book for this leg's input amount: a BUY spends {@code amountIn} of dst, a SELL sells
     * {@code amountIn} of src.
     */
    public OrderBook.Walk walk(OrderBook book, double amountIn) {
        return side == Side.BUY ? book.buyWithBudget(amountIn) : book.sellAmount(amountIn);
    }

    @Override
    public String toString() {
        return side + " " + market.symbol;
    }
}
