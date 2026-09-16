package org.sarh.cycle.engine;

import org.sarh.cycle.config.AppConfig;
import org.sarh.cycle.market.MarketDiscovery.Universe;
import org.sarh.cycle.model.LegResult;
import org.sarh.cycle.model.Market;
import org.sarh.cycle.model.TradeEpisode;
import org.sarh.cycle.model.TriangleExecutionResult;
import org.sarh.cycle.rest.NobitexRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Unwinds inventory stranded by an aborted live triangle.
 *
 * <p>After {@code recovery_delay_s} every non-rial asset the loop left behind is sold on its direct
 * IRT market. The amount sold is the smaller of what the loop recorded as stranded and what the
 * wallet actually holds free, so a recovery can never sell inventory that was there before the
 * loop ran, or that the exchange disagrees about. When everything is back in rial the whole
 * episode -- original legs plus the unwind -- is reported as one P&amp;L row.
 */
public final class RecoveryManager {
    private static final Logger log = LoggerFactory.getLogger(RecoveryManager.class);

    private final AppConfig cfg;
    private final Universe universe;
    private final LiveExecutor executor;
    private final NobitexRestClient rest;
    private final ScheduledExecutorService scheduler;
    private final Consumer<TradeEpisode> onEpisodeComplete;

    public RecoveryManager(AppConfig cfg, Universe universe, LiveExecutor executor, NobitexRestClient rest,
                            ScheduledExecutorService scheduler, Consumer<TradeEpisode> onEpisodeComplete) {
        this.cfg = cfg;
        this.universe = universe;
        this.executor = executor;
        this.rest = rest;
        this.scheduler = scheduler;
        this.onEpisodeComplete = onEpisodeComplete;
    }

    public void schedule(String episodeId, Instant startedAt, TriangleExecutionResult result) {
        Map<String, Double> stranded = result.strandedInventory();
        log.warn("episode {} ({}) left {} stranded; unwinding to rial in {}s", episodeId, result.opportunity.triangle.id(),
                stranded, cfg.recoveryDelayS);
        scheduler.schedule(() -> attempt(episodeId, startedAt, result, stranded, new ArrayList<>(), 1),
                cfg.recoveryDelayS, TimeUnit.SECONDS);
    }

    private void attempt(String episodeId, Instant startedAt, TriangleExecutionResult result,
                          Map<String, Double> remaining, List<LegResult> recoveryLegs, int attemptNo) {
        Map<String, Double> stillStranded = new LinkedHashMap<>();

        for (Map.Entry<String, Double> e : remaining.entrySet()) {
            String currency = e.getKey();
            double amount = e.getValue();
            Market irtMarket = irtMarketFor(currency);
            if (irtMarket == null) {
                log.error("episode {}: no IRT market known for stranded {} -- cannot unwind", episodeId, currency);
                stillStranded.put(currency, amount);
                continue;
            }

            double free;
            try {
                free = rest.freeBalance(currency);
            } catch (Exception ex) {
                log.warn("episode {}: could not read {} balance ({}); will retry", episodeId, currency, ex.toString());
                stillStranded.put(currency, amount);
                continue;
            }
            double toSell = Math.min(amount, free);
            if (toSell <= 0) {
                log.warn("episode {}: wallet shows no free {} to unwind (recorded {}, free {})", episodeId,
                        currency, amount, free);
                stillStranded.put(currency, amount);
                continue;
            }

            LegResult r = executor.sellToRial(irtMarket, toSell);
            recoveryLegs.add(r);
            log.info("episode {} recovery attempt {}: {}", episodeId, attemptNo, r);
            double left = amount - r.amountConsumed;
            if (left > 0 && !r.completed()) {
                stillStranded.put(currency, left);
            }
        }

        if (!stillStranded.isEmpty() && attemptNo < cfg.recoveryMaxAttempts) {
            log.warn("episode {}: {} still stranded after attempt {}; retrying in {}s", episodeId, stillStranded,
                    attemptNo, cfg.recoveryDelayS);
            scheduler.schedule(() -> attempt(episodeId, startedAt, result, stillStranded, recoveryLegs, attemptNo + 1),
                    cfg.recoveryDelayS, TimeUnit.SECONDS);
            return;
        }

        double recovered = recoveryLegs.stream().mapToDouble(l -> l.amountOut).sum();
        boolean anyRecovered = recoveryLegs.stream().anyMatch(LegResult::producedAnything);
        TradeEpisode.Status status = stillStranded.isEmpty()
                ? TradeEpisode.Status.ABORTED_RECOVERED
                : anyRecovered ? TradeEpisode.Status.ABORTED_PARTIAL_RECOVERY : TradeEpisode.Status.ABORTED_UNRECOVERED;
        String note = stillStranded.isEmpty() ? "" : "STILL STRANDED, manual action needed: " + stillStranded;
        if (!stillStranded.isEmpty()) {
            log.error("episode {}: gave up after {} attempts. {}", episodeId, attemptNo, note);
        }

        onEpisodeComplete.accept(new TradeEpisode(
                episodeId, cfg.mode.name(), result.opportunity.triangle, startedAt, Instant.now(), status,
                result.opportunity.netProfitRatio, result.irtSpent(), result.irtReceived(), recovered,
                result.legs, recoveryLegs, note));
    }

    private Market irtMarketFor(String currency) {
        if (currency.equalsIgnoreCase(universe.usdtIrt.src)) return universe.usdtIrt;
        return universe.irtBySymbolBase.get(currency.toUpperCase());
    }
}
