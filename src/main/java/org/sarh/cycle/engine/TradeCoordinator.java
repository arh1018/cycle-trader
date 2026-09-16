package org.sarh.cycle.engine;

import org.sarh.cycle.config.AppConfig;
import org.sarh.cycle.market.MarketDiscovery.Universe;
import org.sarh.cycle.model.Cycle;
import org.sarh.cycle.model.CycleExecutionResult;
import org.sarh.cycle.model.LegResult;
import org.sarh.cycle.model.Market;
import org.sarh.cycle.model.Opportunity;
import org.sarh.cycle.model.OrderBook;
import org.sarh.cycle.model.TradeEpisode;
import org.sarh.cycle.report.PnlReporter;
import org.sarh.cycle.ws.OrderBookCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sits between the detector (websocket thread, fires on every tick) and the executor (slow,
 * blocking, one trade at a time).
 *
 * <ul>
 *   <li>Execution runs on one dedicated thread; the websocket thread only enqueues.
 *   <li>One thread means one cycle at a time -- all cycles draw on the same two quote piles.
 *   <li>A cycle already queued/running is not queued again; after it finishes it is ignored for
 *       {@code cooldown_s}, or {@code loss_cooldown_s} if it lost money.
 *   <li>Before a cycle runs, its start currency is funded from the other pile via USDTIRT if short
 *       ({@code rebalance_notionals} worth), and that conversion is reported as its own row.
 *   <li>Realised P&amp;L over the trailing 24h is tracked; past {@code max_daily_loss_irt} the
 *       coordinator halts and refuses every further opportunity until restart.
 *   <li>{@link #halt()} is the first thing shutdown does, so nothing new can start while the rest
 *       of the process winds down.
 * </ul>
 */
public final class TradeCoordinator {
    private static final Logger log = LoggerFactory.getLogger(TradeCoordinator.class);

    /**
     * Below this rial value, a stranded leftover is pure rounding noise and not worth scheduling a
     * recovery task for. Deliberately far below the exchange's order minimum -- that boundary is
     * for RecoveryManager to discover by trying, not for this check to guess.
     */
    private static final double ROUNDING_DUST_IRT = 5_000;

    private final AppConfig cfg;
    private final Executor executor;
    private final Inventory inventory;
    private final OrderBookCache books;
    private final Universe universe;
    private final PnlReporter reporter;
    private final RecoveryManager recovery; // set after construction (mutual reference)
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "trade-executor");
                        t.setDaemon(true);
                        return t;
                    });

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final Set<String> pausedBases = ConcurrentHashMap.newKeySet();
    private final AtomicLong episodeSeq = new AtomicLong();
    private final Deque<double[]> realised = new ArrayDeque<>(); // {millis, pnlIrt}
    private volatile boolean halted = false;
    private volatile long lastFundingWarnMillis = 0;

    public TradeCoordinator(
            AppConfig cfg,
            Executor executor,
            Inventory inventory,
            OrderBookCache books,
            Universe universe,
            PnlReporter reporter,
            ScheduledExecutorService scheduler) {
        this.cfg = cfg;
        this.executor = executor;
        this.inventory = inventory;
        this.books = books;
        this.universe = universe;
        this.reporter = reporter;
        this.recovery =
                new RecoveryManager(
                        cfg,
                        executor,
                        inventory,
                        scheduler,
                        this::usdtIrtBid,
                        this::usdtIrtAsk,
                        this::onRecoveredEpisode);
        if (cfg.summaryIntervalS > 0) {
            scheduler.scheduleAtFixedRate(
                    () -> {
                        reporter.logSummary();
                        log.info(inventory.describe(usdtIrtBid()));
                    },
                    cfg.summaryIntervalS,
                    cfg.summaryIntervalS,
                    TimeUnit.SECONDS);
        }
    }

    // -- entry points ----------------------------------------------------------------------------

    /** Detector callback. Runs on the websocket thread; must stay cheap and non-blocking. */
    public void onOpportunity(Opportunity opp) {
        if (halted) {
            return;
        }
        String id = opp.cycle.id();
        if (pausedBases.contains(opp.cycle.base)) {
            return;
        }
        Long until = cooldownUntil.get(id);
        if (until != null && System.currentTimeMillis() < until) {
            return;
        }
        if (!inFlight.add(id)) {
            return;
        }

        log.info("OPPORTUNITY {}", opp);
        worker.submit(() -> run(opp));
    }

    /**
     * Sell stray coin balances (worth at least the rial minimum) back to rial. Live mode, at
     * startup.
     */
    public void sweepStrays() {
        worker.submit(
                () -> {
                    for (Map.Entry<String, Double> e : inventory.snapshot().entrySet()) {
                        String coin = e.getKey();
                        double amount = e.getValue();
                        if (Cycle.isQuote(coin) || amount <= 0) {
                            continue;
                        }
                        Market irt = universe.irtBySymbolBase.get(coin.toUpperCase());
                        OrderBook book = irt == null ? null : books.get(irt.symbol);
                        if (book == null) {
                            continue;
                        }
                        double valueIrt = amount * book.bestBid();
                        if (valueIrt < cfg.minOrderRial) {
                            continue;
                        }

                        String episodeId = nextEpisodeId();
                        Instant started = Instant.now();
                        log.warn(
                                "episode {}: sweeping stray {} {} (≈{} IRT) to rial",
                                episodeId,
                                amount,
                                coin,
                                String.format("%,.0f", valueIrt));
                        LegResult r = executor.sell(irt, amount);
                        record(
                                new TradeEpisode(
                                        episodeId,
                                        cfg.mode.name(),
                                        TradeEpisode.Kind.SWEEP,
                                        "SWEEP " + coin.toUpperCase(),
                                        coin.toUpperCase() + ">IRT",
                                        started,
                                        Instant.now(),
                                        r.producedAnything()
                                                ? TradeEpisode.Status.SWEPT
                                                : TradeEpisode.Status.SWEEP_FAILED,
                                        0,
                                        coin,
                                        r.amountConsumed,
                                        Cycle.RIAL,
                                        r.amountOut,
                                        0,
                                        usdtIrtBid(),
                                        usdtIrtAsk(),
                                        List.of(r),
                                        List.of(),
                                        r.producedAnything()
                                                ? "origin unknown; P&L not attributable"
                                                : r.note));
                    }
                });
    }

    public void halt() {
        halted = true;
    }

    public void shutdown() {
        halt();
        worker.shutdownNow();
        reporter.logSummary();
        log.info(inventory.describe(usdtIrtBid()));
    }

    // -- one cycle -------------------------------------------------------------------------------

    private void run(Opportunity opp) {
        String id = opp.cycle.id();
        String episodeId = nextEpisodeId();
        Instant startedAt = Instant.now();
        boolean lost = false;
        try {
            if (halted) {
                return;
            }
            if (!ensureFunding(opp)) {
                return;
            }
            CycleExecutionResult result = executor.execute(opp);
            lost = settle(episodeId, startedAt, result);
        } catch (Exception e) {
            log.error(
                    "episode {} ({}) raised -- inspect the account before continuing",
                    episodeId,
                    id,
                    e);
        } finally {
            long cd = (lost ? cfg.lossCooldownS : cfg.cooldownS) * 1000L;
            cooldownUntil.put(id, System.currentTimeMillis() + cd);
            inFlight.remove(id);
        }
    }

    /**
     * Make sure the cycle's start currency is available, converting from the other quote pile via
     * USDTIRT if not. Returns false if the cycle cannot be funded.
     */
    private boolean ensureFunding(Opportunity opp) {
        String start = opp.cycle.startCurrency();
        double need = opp.startAmount * 1.002;
        if (inventory.get(start) >= need) {
            return true;
        }

        Market fx = universe.usdtIrt;
        OrderBook fxBook = books.get(fx.symbol);
        if (fxBook == null || !fxBook.isSane()) {
            return warnFunding(opp, "no USDTIRT book to rebalance with");
        }

        double target = need * cfg.rebalanceNotionals;
        double deficit = target - inventory.get(start);
        String episodeId = nextEpisodeId();
        Instant started = Instant.now();
        LegResult r;
        String from, to;
        if (Cycle.RIAL.equals(start)) {
            // need rial: sell USDT
            double usdtToSell = Math.min(deficit / fxBook.bestBid(), inventory.get(Cycle.USDT));
            if (usdtToSell * fxBook.bestBid() < cfg.minOrderRial) {
                return warnFunding(opp, "not enough USDT to rebalance");
            }
            from = Cycle.USDT;
            to = Cycle.RIAL;
            r = executor.sell(fx, usdtToSell);
        } else {
            // need USDT: buy it with rial
            double rialBudget = Math.min(deficit * fxBook.bestAsk(), inventory.get(Cycle.RIAL));
            if (rialBudget < cfg.minOrderRial) {
                return warnFunding(opp, "not enough rial to rebalance");
            }
            from = Cycle.RIAL;
            to = Cycle.USDT;
            r = executor.buy(fx, rialBudget);
        }
        record(
                new TradeEpisode(
                        episodeId,
                        cfg.mode.name(),
                        TradeEpisode.Kind.REBALANCE,
                        "REBALANCE " + Cycle.label(from) + ">" + Cycle.label(to),
                        Cycle.label(from) + ">" + Cycle.label(to),
                        started,
                        Instant.now(),
                        r.producedAnything()
                                ? TradeEpisode.Status.REBALANCED
                                : TradeEpisode.Status.REBALANCE_FAILED,
                        0,
                        from,
                        r.amountConsumed,
                        to,
                        r.amountOut,
                        0,
                        usdtIrtBid(),
                        usdtIrtAsk(),
                        List.of(r),
                        List.of(),
                        r.producedAnything() ? "" : r.note));

        if (inventory.get(start) >= opp.startAmount) {
            return true;
        }
        return warnFunding(
                opp,
                String.format(
                        "still short after rebalance: have %.4f need %.4f %s",
                        inventory.get(start), opp.startAmount, start));
    }

    private boolean warnFunding(Opportunity opp, String why) {
        long now = System.currentTimeMillis();
        if (now - lastFundingWarnMillis > 60_000) {
            lastFundingWarnMillis = now;
            log.warn(
                    "cannot fund {}: {}. {}",
                    opp.cycle.id(),
                    why,
                    inventory.describe(usdtIrtBid()));
        }
        return false;
    }

    /** Report or hand to recovery. Returns true if the episode was recorded as a loss. */
    private boolean settle(String episodeId, Instant startedAt, CycleExecutionResult result) {
        Opportunity opp = result.opportunity;
        Cycle c = opp.cycle;
        double stranded = result.strandedMid();
        double strandedValueIrt =
                stranded * opp.legMarginal[1] * (c.legs.get(1).isRialQuoted() ? 1 : usdtIrtBid());

        if (result.startSpent() <= 0) {
            return record(
                    new TradeEpisode(
                            episodeId,
                            cfg.mode.name(),
                            TradeEpisode.Kind.CYCLE,
                            c.id(),
                            c.path(),
                            startedAt,
                            Instant.now(),
                            TradeEpisode.Status.ABORTED_NOTHING_SPENT,
                            opp.netProfitRatio,
                            c.startCurrency(),
                            0,
                            c.endCurrency(),
                            0,
                            0,
                            usdtIrtBid(),
                            usdtIrtAsk(),
                            result.legs,
                            List.of(),
                            result.legs.get(0).note));
        }

        // Escalate whenever there is anything worth trying to sell, not just when it already
        // clears the exchange minimum: a partial fill can strand a meaningful fraction of the
        // trade (9% of a 5,000,000 IRT cycle is 450,000 -- under the ~550,000 minimum but far
        // from nothing) and RecoveryManager's own sell attempt is what actually knows whether the
        // exchange will accept it. Only skip scheduling for genuine rounding dust.
        if (strandedValueIrt >= ROUNDING_DUST_IRT) {
            pausedBases.add(c.base);
            recovery.schedule(episodeId, startedAt, result);
            return false; // reported when recovery completes
        }

        String note =
                stranded > 0
                        ? String.format(
                                "%.8f %s (%.0f IRT) rounding dust left",
                                stranded, c.midCurrency(), strandedValueIrt)
                        : "";
        return record(
                new TradeEpisode(
                        episodeId,
                        cfg.mode.name(),
                        TradeEpisode.Kind.CYCLE,
                        c.id(),
                        c.path(),
                        startedAt,
                        Instant.now(),
                        TradeEpisode.Status.FILLED,
                        opp.netProfitRatio,
                        c.startCurrency(),
                        result.startSpent(),
                        c.endCurrency(),
                        result.endReceived(),
                        0,
                        usdtIrtBid(),
                        usdtIrtAsk(),
                        result.legs,
                        List.of(),
                        note));
    }

    /** RecoveryManager callback: report the finished episode and un-pause its base coin. */
    private void onRecoveredEpisode(TradeEpisode ep) {
        try {
            boolean lost = record(ep);
            if (lost) {
                String cycleId = ep.pathLabel;
                cooldownUntil.put(cycleId, System.currentTimeMillis() + cfg.lossCooldownS * 1000L);
            }
        } finally {
            String base =
                    ep.pathLabel.contains("/")
                            ? ep.pathLabel.substring(0, ep.pathLabel.indexOf('/')).toLowerCase()
                            : "";
            pausedBases.remove(base);
        }
    }

    /**
     * Write the row, track realised P&amp;L, enforce the daily loss cap. Returns true on a loss.
     */
    private boolean record(TradeEpisode ep) {
        reporter.record(ep);
        double pnl = ep.pnlIrt();
        if (cfg.maxDailyLossIrt > 0 && ep.kind != TradeEpisode.Kind.SWEEP) {
            synchronized (realised) {
                long now = System.currentTimeMillis();
                realised.addLast(new double[] {now, pnl});
                while (!realised.isEmpty() && now - realised.peekFirst()[0] > 86_400_000L) {
                    realised.pollFirst();
                }
                double net = realised.stream().mapToDouble(x -> x[1]).sum();
                if (net <= -cfg.maxDailyLossIrt && !halted) {
                    halted = true;
                    log.error(
                            "DAILY LOSS CAP HIT: realised {} IRT over 24h (cap -{}). Trading halted"
                                    + " until restart.",
                            String.format("%,.0f", net),
                            String.format("%,.0f", cfg.maxDailyLossIrt));
                }
            }
        }
        return pnl < 0;
    }

    private String nextEpisodeId() {
        return String.format("E%05d", episodeSeq.incrementAndGet());
    }

    private double usdtIrtBid() {
        OrderBook b = books.get(universe.usdtIrt.symbol);
        return b == null ? 0 : b.bestBid();
    }

    private double usdtIrtAsk() {
        OrderBook b = books.get(universe.usdtIrt.symbol);
        return b == null ? 0 : b.bestAsk();
    }
}
