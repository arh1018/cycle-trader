package org.sarh.cycle.engine;

import org.sarh.cycle.config.AppConfig;
import org.sarh.cycle.model.CycleExecutionResult;
import org.sarh.cycle.model.CycleLeg;
import org.sarh.cycle.model.LegResult;
import org.sarh.cycle.model.Market;
import org.sarh.cycle.model.Opportunity;
import org.sarh.cycle.model.OrderBook;
import org.sarh.cycle.model.Side;
import org.sarh.cycle.ws.OrderBookCache;

import java.util.List;

/**
 * Simulates fills by walking the live book for the real size, net of fee and the slippage haircut,
 * and applies them to the local inventory. Never touches the exchange, never fails a leg that the
 * book can absorb -- so paper P&amp;L is an upper bound on what the same signals would earn live.
 */
public final class PaperExecutor implements Executor {
    private final AppConfig cfg;
    private final OrderBookCache books;
    private final Inventory inventory;

    public PaperExecutor(AppConfig cfg, OrderBookCache books, Inventory inventory) {
        this.cfg = cfg;
        this.books = books;
        this.inventory = inventory;
    }

    @Override
    public CycleExecutionResult execute(Opportunity opp) {
        LegResult r1 = fill(opp.cycle.legs.get(0), opp.startAmount);
        if (!r1.producedAnything()) {
            return new CycleExecutionResult(
                    opp,
                    List.of(
                            r1,
                            LegResult.skipped(opp.cycle.legs.get(1), 0, "leg 1 produced nothing")));
        }
        LegResult r2 = fill(opp.cycle.legs.get(1), r1.amountOut);
        return new CycleExecutionResult(opp, List.of(r1, r2));
    }

    @Override
    public LegResult sell(Market market, double srcAmount) {
        return fill(new CycleLeg(market, Side.SELL), srcAmount);
    }

    @Override
    public LegResult buy(Market market, double dstBudget) {
        return fill(new CycleLeg(market, Side.BUY), dstBudget);
    }

    private LegResult fill(CycleLeg leg, double amountIn) {
        if (!(amountIn > 0)) {
            return LegResult.failed(leg, amountIn, "nothing to convert");
        }
        OrderBook book = books.get(leg.market.symbol);
        if (book == null
                || book.isStale(System.currentTimeMillis(), cfg.maxBookAgeMillis)
                || !book.isSane()) {
            return LegResult.failed(leg, amountIn, "no live book for " + leg.market.symbol);
        }
        OrderBook.Walk w = leg.walk(book, amountIn);
        if (w.srcAmount() <= 0) {
            return LegResult.failed(leg, amountIn, "empty book");
        }

        double cost = (leg.isRialQuoted() ? cfg.takerFeeIrt : cfg.takerFeeUsdt) + cfg.slippage;
        double consumed = leg.side == Side.BUY ? w.dstAmount() : w.srcAmount();
        double grossOut = leg.side == Side.BUY ? w.srcAmount() : w.dstAmount();
        double out = grossOut * (1 - cost);

        inventory.applyFill(leg.inputCurrency(), consumed, leg.outputCurrency(), out);
        // ordered == matched: paper fills whatever the walk covered
        return LegResult.filled(
                leg, amountIn, w.srcAmount(), w.srcAmount(), consumed, out, w.vwap(), null);
    }
}
