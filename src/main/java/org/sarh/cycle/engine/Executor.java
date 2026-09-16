package org.sarh.cycle.engine;

import org.sarh.cycle.model.CycleExecutionResult;
import org.sarh.cycle.model.LegResult;
import org.sarh.cycle.model.Market;
import org.sarh.cycle.model.Opportunity;

/** Places (or simulates) orders and keeps the {@link Inventory} in step with every fill. */
public interface Executor {

    /** Run both legs of a cycle in order, leg 2 sized from leg 1's actual net proceeds. */
    CycleExecutionResult execute(Opportunity opportunity);

    /** Sell {@code srcAmount} of {@code market.src} into {@code market.dst} at the current book. */
    LegResult sell(Market market, double srcAmount);

    /**
     * Buy {@code market.src} spending up to {@code dstBudget} of {@code market.dst} at the current
     * book.
     */
    LegResult buy(Market market, double dstBudget);
}
