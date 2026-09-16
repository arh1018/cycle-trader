package org.sarh.cycle.engine;

import org.sarh.cycle.model.LegResult;
import org.sarh.cycle.model.Opportunity;
import org.sarh.cycle.model.Side;
import org.sarh.cycle.model.TriangleExecutionResult;
import org.sarh.cycle.model.TriangleLeg;

import java.util.ArrayList;
import java.util.List;

/**
 * Simulates fills at the same top-of-book prices the detector used, net of the configured fee and
 * slippage haircut. Never touches the exchange, never fails a leg -- so paper P&amp;L is an upper
 * bound on what the same signals would have earned live.
 */
public final class PaperExecutor implements Executor {

    @Override
    public TriangleExecutionResult execute(Opportunity opp) {
        List<LegResult> legs = new ArrayList<>(3);
        double amountIn = opp.startAmountIrt;
        for (int i = 0; i < opp.triangle.legs.size(); i++) {
            TriangleLeg leg = opp.triangle.legs.get(i);
            double amountOut = opp.legAmountsOut[i];
            double srcAmount = leg.side == Side.BUY ? amountIn / opp.legPrices[i] : amountIn;
            legs.add(LegResult.filled(leg, amountIn, srcAmount, srcAmount, amountIn, amountOut, opp.legPrices[i], null));
            amountIn = amountOut;
        }
        return new TriangleExecutionResult(opp, legs);
    }
}
