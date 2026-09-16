package org.sarh.cycle.model;

import java.util.List;

/**
 * A two-leg conversion between the account's two quote currencies through one coin.
 *
 * <pre>
 *   IRT_TO_USDT :  rial --buy COINIRT--&gt; COIN --sell COINUSDT--&gt; USDT
 *   USDT_TO_IRT :  USDT --buy COINUSDT--&gt; COIN --sell COINIRT--&gt; rial
 * </pre>
 *
 * <p>Because the account holds both rial and USDT, neither direction needs a USDTIRT leg: a cycle
 * simply moves value from one quote pile to the other, and its profit is measured by valuing the
 * end pile in rial at the live USDTIRT rate. The two directions refill each other; USDTIRT is only
 * traded to rebalance when one pile runs dry.
 *
 * <p>The constructor verifies the currency chain so a mis-built cycle fails here, not as a rejected
 * order.
 */
public final class Cycle {
    public enum Direction {
        IRT_TO_USDT,
        USDT_TO_IRT
    }

    public static final String RIAL = "rls";
    public static final String USDT = "usdt";

    public final String base; // e.g. "btc"
    public final Direction direction;
    public final List<CycleLeg> legs; // exactly 2, in execution order

    public Cycle(String base, Direction direction, List<CycleLeg> legs) {
        if (legs.size() != 2) {
            throw new IllegalArgumentException("a cycle has exactly 2 legs, got " + legs.size());
        }
        String start = legs.get(0).inputCurrency(), mid = legs.get(0).outputCurrency();
        String mid2 = legs.get(1).inputCurrency(), end = legs.get(1).outputCurrency();
        String id = base + "/" + direction;
        if (!mid.equals(mid2)) {
            throw new IllegalArgumentException(
                    id + ": leg 1 produces " + mid + " but leg 2 consumes " + mid2);
        }
        if (!isQuote(start) || !isQuote(end) || start.equals(end)) {
            throw new IllegalArgumentException(
                    id + ": must run between rls and usdt, runs " + start + " -> " + end);
        }
        boolean expectStartRial = direction == Direction.IRT_TO_USDT;
        if (RIAL.equals(start) != expectStartRial) {
            throw new IllegalArgumentException(
                    id + ": direction does not match legs (" + start + " -> " + end + ")");
        }
        this.base = base;
        this.direction = direction;
        this.legs = List.copyOf(legs);
    }

    public static boolean isQuote(String currency) {
        return RIAL.equals(currency) || USDT.equals(currency);
    }

    public String startCurrency() {
        return legs.get(0).inputCurrency();
    }

    public String midCurrency() {
        return legs.get(0).outputCurrency();
    }

    public String endCurrency() {
        return legs.get(1).outputCurrency();
    }

    public String id() {
        return base.toUpperCase()
                + "/"
                + (direction == Direction.IRT_TO_USDT ? "IRT>USDT" : "USDT>IRT");
    }

    /** Human-readable currency path, e.g. {@code IRT>BTC>USDT}. */
    public String path() {
        return label(startCurrency()) + ">" + base.toUpperCase() + ">" + label(endCurrency());
    }

    public static String label(String currency) {
        return RIAL.equals(currency) ? "IRT" : currency.toUpperCase();
    }

    public List<String> symbols() {
        return legs.stream().map(l -> l.market.symbol).toList();
    }

    @Override
    public String toString() {
        return id() + " " + legs;
    }
}
