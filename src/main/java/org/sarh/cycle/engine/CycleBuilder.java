package org.sarh.cycle.engine;

import org.sarh.cycle.market.MarketDiscovery.Universe;
import org.sarh.cycle.model.Cycle;
import org.sarh.cycle.model.CycleLeg;
import org.sarh.cycle.model.Market;
import org.sarh.cycle.model.Side;

import java.util.ArrayList;
import java.util.List;

/** Builds both cycle directions for every discovered base coin. */
public final class CycleBuilder {

    public static List<Cycle> build(Universe universe) {
        List<Cycle> cycles = new ArrayList<>();
        for (String base : universe.bases) {
            Market irt = universe.irtBySymbolBase.get(base);
            Market usdt = universe.usdtBySymbolBase.get(base);
            if (irt == null || usdt == null) {
                continue;
            }

            cycles.add(
                    new Cycle(
                            base,
                            Cycle.Direction.IRT_TO_USDT,
                            List.of(new CycleLeg(irt, Side.BUY), new CycleLeg(usdt, Side.SELL))));

            cycles.add(
                    new Cycle(
                            base,
                            Cycle.Direction.USDT_TO_IRT,
                            List.of(new CycleLeg(usdt, Side.BUY), new CycleLeg(irt, Side.SELL))));
        }
        return cycles;
    }
}
