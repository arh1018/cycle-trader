package org.sarh.cycle.engine;

import org.sarh.cycle.market.MarketDiscovery.Universe;
import org.sarh.cycle.model.Market;
import org.sarh.cycle.model.Side;
import org.sarh.cycle.model.Triangle;
import org.sarh.cycle.model.TriangleLeg;

import java.util.ArrayList;
import java.util.List;

/** Builds the two IRT-anchored triangles (forward and reverse) for every discovered base coin. */
public final class TriangleBuilder {

    public static List<Triangle> build(Universe universe) {
        List<Triangle> triangles = new ArrayList<>();
        Market usdtIrt = universe.usdtIrt;

        for (String base : universe.bases) {
            Market irt = universe.irtBySymbolBase.get(base);
            Market usdt = universe.usdtBySymbolBase.get(base);
            if (irt == null || usdt == null) continue;

            // FORWARD: IRT -> BASE -> USDT -> IRT
            triangles.add(new Triangle(base, Triangle.Direction.FORWARD, List.of(
                    new TriangleLeg(irt, Side.BUY),
                    new TriangleLeg(usdt, Side.SELL),
                    new TriangleLeg(usdtIrt, Side.SELL)
            )));

            // REVERSE: IRT -> USDT -> BASE -> IRT
            triangles.add(new Triangle(base, Triangle.Direction.REVERSE, List.of(
                    new TriangleLeg(usdtIrt, Side.BUY),
                    new TriangleLeg(usdt, Side.BUY),
                    new TriangleLeg(irt, Side.SELL)
            )));
        }
        return triangles;
    }
}
