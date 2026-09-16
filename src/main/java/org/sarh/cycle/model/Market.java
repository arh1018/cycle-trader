package org.sarh.cycle.model;

/**
 * One tradeable Nobitex market, e.g. {@code BTCIRT} (src=btc, dst=rls) or
 * {@code BTCUSDT} (src=btc, dst=usdt).
 */
public final class Market {
    public final String symbol;      // e.g. "BTCIRT"
    public final String src;         // srcCurrency for /market/orders/add, e.g. "btc"
    public final String dst;         // dstCurrency, e.g. "rls" or "usdt"
    public final double amountStep;  // order size rounding
    public final double latest24h;   // last trade price, for volume ranking
    public final double volume24hDst; // 24h volume in dst currency

    public Market(String symbol, String src, String dst, double amountStep,
                   double latest24h, double volume24hDst) {
        this.symbol = symbol;
        this.src = src;
        this.dst = dst;
        this.amountStep = amountStep;
        this.latest24h = latest24h;
        this.volume24hDst = volume24hDst;
    }

    public boolean isRial() {
        return "rls".equals(dst);
    }
}
