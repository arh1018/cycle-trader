package org.sarh.cycle.engine;

import org.sarh.cycle.config.AppConfig;
import org.sarh.cycle.model.Cycle;
import org.sarh.cycle.model.CycleExecutionResult;
import org.sarh.cycle.model.LegResult;
import org.sarh.cycle.model.Market;
import org.sarh.cycle.model.TradeEpisode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/**
 * Unwinds a coin stranded by a cycle whose second leg failed.
 *
 * <p>After {@code recovery_delay_s} the coin is sold back into the cycle's <em>start</em> currency
 * on the same market leg 1 bought it on -- "reset to source". The amount sold is the smaller of
 * what the cycle recorded and what the inventory shows, so a recovery cannot sell holdings the
 * cycle did not create. When done, the whole episode -- both legs plus the unwind -- is reported as
 * one realised P&amp;L row.
 *
 * <p>Whether a leftover is genuinely unsellable ("dust") is decided by the exchange minimum, not by
 * a value guessed here: {@link LiveExecutor}'s own minimum-notional check already knows each
 * market's real floor in its own unit, so a sell failing with that specific reason is treated as
 * terminal. An earlier version re-derived the same answer from {@code legMarginal}, comparing a
 * USDT-denominated quantity against a rial threshold (or vice versa) -- wrong in both directions,
 * and it once closed a 4,975,000 IRT loss as "recovered" after a single failed attempt while the
 * coin sat untouched in the wallet.
 */
public final class RecoveryManager {
    private static final Logger log = LoggerFactory.getLogger(RecoveryManager.class);

    private final AppConfig cfg;
    private final Executor executor;
    private final Inventory inventory;
    private final ScheduledExecutorService scheduler;
    private final Consumer<TradeEpisode> onEpisodeComplete;
    private final DoubleSupplier usdtIrtBid, usdtIrtAsk;

    public RecoveryManager(
            AppConfig cfg,
            Executor executor,
            Inventory inventory,
            ScheduledExecutorService scheduler,
            DoubleSupplier usdtIrtBid,
            DoubleSupplier usdtIrtAsk,
            Consumer<TradeEpisode> onEpisodeComplete) {
        this.cfg = cfg;
        this.executor = executor;
        this.inventory = inventory;
        this.scheduler = scheduler;
        this.usdtIrtBid = usdtIrtBid;
        this.usdtIrtAsk = usdtIrtAsk;
        this.onEpisodeComplete = onEpisodeComplete;
    }

    public void schedule(String episodeId, Instant startedAt, CycleExecutionResult result) {
        Cycle c = result.opportunity.cycle;
        double stranded = result.strandedMid();
        log.warn(
                "episode {} ({}) left {} {} stranded; selling back to {} in {}s",
                episodeId,
                c.id(),
                stranded,
                c.midCurrency(),
                Cycle.label(c.startCurrency()),
                cfg.recoveryDelayS);
        scheduler.schedule(
                () -> attempt(episodeId, startedAt, result, stranded, new ArrayList<>(), 1),
                cfg.recoveryDelayS,
                TimeUnit.SECONDS);
    }

    private void attempt(
            String episodeId,
            Instant startedAt,
            CycleExecutionResult result,
            double remaining,
            List<LegResult> recoveryLegs,
            int attemptNo) {
        Cycle c = result.opportunity.cycle;
        Market market = c.legs.get(0).market; // leg 1 bought the coin here; sell it back here
        double held = inventory.get(c.midCurrency());
        double toSell = Math.min(remaining, held);

        double left = remaining;
        boolean unsellable = false;
        if (toSell > 0) {
            LegResult r = executor.sell(market, toSell);
            recoveryLegs.add(r);
            log.info("episode {} recovery attempt {}: {}", episodeId, attemptNo, r);
            left = remaining - r.amountConsumed;
            unsellable =
                    r.status == LegResult.Status.FAILED
                            && r.note != null
                            && r.note.contains("below exchange minimum");
        } else {
            log.warn(
                    "episode {}: inventory shows no free {} to unwind (recorded {}, held {})",
                    episodeId,
                    c.midCurrency(),
                    remaining,
                    held);
        }

        if (left > 0 && !unsellable && attemptNo < cfg.recoveryMaxAttempts) {
            log.warn(
                    "episode {}: {} {} still stranded after attempt {}; retrying in {}s",
                    episodeId,
                    left,
                    c.midCurrency(),
                    attemptNo,
                    cfg.recoveryDelayS);
            double leftFinal = left;
            scheduler.schedule(
                    () ->
                            attempt(
                                    episodeId,
                                    startedAt,
                                    result,
                                    leftFinal,
                                    recoveryLegs,
                                    attemptNo + 1),
                    cfg.recoveryDelayS,
                    TimeUnit.SECONDS);
            return;
        }

        double recovered = recoveryLegs.stream().mapToDouble(l -> l.amountOut).sum();
        TradeEpisode.Status status;
        String note;
        if (left <= 0) {
            status = TradeEpisode.Status.ABORTED_RECOVERED;
            note = "";
        } else if (unsellable) {
            // Below the exchange's own minimum notional: no retry would help without more of this
            // coin joining it. Still stranded, still worth a rial value, just not sellable alone.
            status =
                    recovered > 0
                            ? TradeEpisode.Status.ABORTED_PARTIAL_RECOVERY
                            : TradeEpisode.Status.ABORTED_UNRECOVERED;
            note =
                    String.format(
                            "STILL STRANDED (below exchange minimum, needs manual consolidation or"
                                    + " price move): %.8f %s",
                            left, c.midCurrency());
        } else if (recovered > 0) {
            status = TradeEpisode.Status.ABORTED_PARTIAL_RECOVERY;
            note =
                    String.format(
                            "STILL STRANDED, manual action needed: %.8f %s", left, c.midCurrency());
        } else {
            status = TradeEpisode.Status.ABORTED_UNRECOVERED;
            note =
                    String.format(
                            "STILL STRANDED, manual action needed: %.8f %s", left, c.midCurrency());
        }
        if (status != TradeEpisode.Status.ABORTED_RECOVERED) {
            log.error("episode {}: gave up after {} attempts. {}", episodeId, attemptNo, note);
        }

        onEpisodeComplete.accept(
                new TradeEpisode(
                        episodeId,
                        cfg.mode.name(),
                        TradeEpisode.Kind.CYCLE,
                        c.id(),
                        c.path(),
                        startedAt,
                        Instant.now(),
                        status,
                        result.opportunity.netProfitRatio,
                        c.startCurrency(),
                        result.startSpent(),
                        c.endCurrency(),
                        result.endReceived(),
                        recovered,
                        usdtIrtBid.getAsDouble(),
                        usdtIrtAsk.getAsDouble(),
                        result.legs,
                        recoveryLegs,
                        note));
    }
}
