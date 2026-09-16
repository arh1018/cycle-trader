package org.sarh.cycle.model;

import java.util.List;

/**
 * A closed IRT -&gt; ... -&gt; IRT loop through exactly three markets.
 *
 * <p>Two directions exist per base coin:
 * <ul>
 *   <li>{@code FORWARD}:  IRT -&gt; BASE -&gt; USDT -&gt; IRT
 *       (buy BASE with rial, sell BASE for USDT, sell USDT for rial)</li>
 *   <li>{@code REVERSE}:  IRT -&gt; USDT -&gt; BASE -&gt; IRT
 *       (buy USDT with rial, buy BASE with USDT, sell BASE for rial)</li>
 * </ul>
 *
 * <p>The constructor verifies the currency chain: each leg must consume exactly what the previous
 * leg produced, and the loop must start and end in rial. A triangle that fails this check is a
 * construction bug, and it is far cheaper to fail here than to discover it as a rejected order.
 */
public final class Triangle {
    public enum Direction { FORWARD, REVERSE }

    public static final String RIAL = "rls";

    public final String base;          // e.g. "btc"
    public final Direction direction;
    public final List<TriangleLeg> legs; // exactly 3, in execution order

    public Triangle(String base, Direction direction, List<TriangleLeg> legs) {
        if (legs.size() != 3) {
            throw new IllegalArgumentException("a triangle has exactly 3 legs, got " + legs.size());
        }
        if (!RIAL.equals(legs.get(0).inputCurrency())) {
            throw new IllegalArgumentException(base + "/" + direction + ": first leg must spend rial, spends "
                    + legs.get(0).inputCurrency());
        }
        for (int i = 1; i < legs.size(); i++) {
            String produced = legs.get(i - 1).outputCurrency();
            String consumed = legs.get(i).inputCurrency();
            if (!produced.equals(consumed)) {
                throw new IllegalArgumentException(base + "/" + direction + ": leg " + i + " produces "
                        + produced + " but leg " + (i + 1) + " consumes " + consumed);
            }
        }
        if (!RIAL.equals(legs.get(2).outputCurrency())) {
            throw new IllegalArgumentException(base + "/" + direction + ": last leg must produce rial, produces "
                    + legs.get(2).outputCurrency());
        }
        this.base = base;
        this.direction = direction;
        this.legs = List.copyOf(legs);
    }

    public String id() {
        return base.toUpperCase() + "/" + direction;
    }

    /** Human-readable currency path, e.g. {@code IRT>BTC>USDT>IRT}. */
    public String path() {
        StringBuilder sb = new StringBuilder("IRT");
        for (TriangleLeg leg : legs) {
            String out = leg.outputCurrency();
            sb.append('>').append(RIAL.equals(out) ? "IRT" : out.toUpperCase());
        }
        return sb.toString();
    }

    /** The market symbols this triangle depends on, for the "which triangles does a book update
     *  affect" index. */
    public List<String> symbols() {
        return legs.stream().map(l -> l.market.symbol).toList();
    }

    @Override
    public String toString() {
        return id() + " " + legs;
    }
}
