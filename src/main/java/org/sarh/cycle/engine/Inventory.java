package org.sarh.cycle.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The engine's own view of free balances, by currency.
 *
 * <p>Fills update it immediately from the order's reported amounts, because the exchange's wallet
 * endpoint lags fills by several seconds and sizing the next order from it would either strand dust
 * or get an order rejected. The wallet is re-read only when nothing has traded for a while, and
 * then it wins.
 */
public final class Inventory {
    private static final Logger log = LoggerFactory.getLogger(Inventory.class);

    private final ConcurrentHashMap<String, Double> free = new ConcurrentHashMap<>();
    private volatile long lastTradeMillis = 0;

    public double get(String currency) {
        return free.getOrDefault(currency.toLowerCase(), 0d);
    }

    public void set(String currency, double amount) {
        free.put(currency.toLowerCase(), Math.max(0, amount));
    }

    /**
     * Apply a fill: {@code -consumed} of the input currency, {@code +out} of the output currency.
     */
    public void applyFill(String inCurrency, double consumed, String outCurrency, double out) {
        free.merge(inCurrency.toLowerCase(), -consumed, (a, b) -> Math.max(0, a + b));
        free.merge(outCurrency.toLowerCase(), out, Double::sum);
        lastTradeMillis = System.currentTimeMillis();
    }

    public void markTrade() {
        lastTradeMillis = System.currentTimeMillis();
    }

    /**
     * Replace every balance with the wallet's, unless a trade happened within {@code quietMillis}.
     */
    public boolean reconcile(Function<Void, Map<String, Double>> walletReader, long quietMillis) {
        if (System.currentTimeMillis() - lastTradeMillis < quietMillis) {
            return false;
        }
        Map<String, Double> wallet = walletReader.apply(null);
        free.clear();
        wallet.forEach((c, v) -> free.put(c.toLowerCase(), v));
        return true;
    }

    public Map<String, Double> snapshot() {
        return new TreeMap<>(free);
    }

    public String describe(double usdtIrtBid) {
        double rls = get("rls"), usdt = get("usdt");
        StringBuilder sb =
                new StringBuilder(
                        String.format(
                                Locale.ROOT,
                                "inventory: %,.0f IRT + %.4f USDT (≈%,.0f IRT)",
                                rls,
                                usdt,
                                rls + usdt * usdtIrtBid));
        free.forEach(
                (c, v) -> {
                    if (!c.equals("rls") && !c.equals("usdt") && v > 0) {
                        sb.append(String.format(Locale.ROOT, ", %s=%.6f", c, v));
                    }
                });
        return sb.toString();
    }

    void logSnapshot(double usdtIrtBid) {
        log.info(describe(usdtIrtBid));
    }
}
