package org.sarh.cycle.engine;

import org.sarh.cycle.config.AppConfig;
import org.sarh.cycle.model.Opportunity;
import org.sarh.cycle.model.TradeEpisode;
import org.sarh.cycle.model.TriangleExecutionResult;
import org.sarh.cycle.report.PnlReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
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
 * blocking, must run once at a time).
 *
 * <ul>
 *   <li>Execution happens on a single dedicated thread. The websocket thread only ever enqueues,
 *       so a 45-second live leg can never starve the 25-second Centrifugo ping.</li>
 *   <li>One thread also means one triangle at a time: all triangles draw on the same rial wallet,
 *       so running two concurrently would double-spend the balance check.</li>
 *   <li>A triangle already queued or running is not queued again, and after it finishes it is
 *       ignored for {@code cooldown_s}. Without this, one persistent quote fires on every tick.</li>
 *   <li>A base coin with a recovery pending is paused so stranded positions cannot stack up.</li>
 * </ul>
 */
public final class TradeCoordinator {
    private static final Logger log = LoggerFactory.getLogger(TradeCoordinator.class);

    private final AppConfig cfg;
    private final Executor executor;
    private final PnlReporter reporter;
    private final RecoveryManager recovery; // null in paper mode
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "trade-executor");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService scheduler;

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final Set<String> pausedBases = ConcurrentHashMap.newKeySet();
    private final AtomicLong episodeSeq = new AtomicLong();

    public TradeCoordinator(AppConfig cfg, Executor executor, PnlReporter reporter, RecoveryManager recovery,
                             ScheduledExecutorService scheduler) {
        this.cfg = cfg;
        this.executor = executor;
        this.reporter = reporter;
        this.recovery = recovery;
        this.scheduler = scheduler;
        if (cfg.summaryIntervalS > 0) {
            scheduler.scheduleAtFixedRate(reporter::logSummary, cfg.summaryIntervalS, cfg.summaryIntervalS,
                    TimeUnit.SECONDS);
        }
    }

    /** Detector callback. Runs on the websocket thread; must stay cheap and non-blocking. */
    public void onOpportunity(Opportunity opp) {
        String id = opp.triangle.id();
        if (pausedBases.contains(opp.triangle.base)) return;
        Long until = cooldownUntil.get(id);
        if (until != null && System.currentTimeMillis() < until) return;
        if (!inFlight.add(id)) return;

        log.info("OPPORTUNITY {}", opp);
        worker.submit(() -> run(opp));
    }

    private void run(Opportunity opp) {
        String id = opp.triangle.id();
        String episodeId = String.format("E%05d", episodeSeq.incrementAndGet());
        Instant startedAt = Instant.now();
        try {
            TriangleExecutionResult result = executor.execute(opp);
            settle(episodeId, startedAt, result);
        } catch (Exception e) {
            log.error("episode {} ({}) raised -- inspect the account before continuing", episodeId, id, e);
        } finally {
            cooldownUntil.put(id, System.currentTimeMillis() + cfg.cooldownS * 1000L);
            inFlight.remove(id);
        }
    }

    private void settle(String episodeId, Instant startedAt, TriangleExecutionResult result) {
        Opportunity opp = result.opportunity;
        if (result.fullyFilled()) {
            reporter.record(new TradeEpisode(episodeId, cfg.mode.name(), opp.triangle, startedAt, Instant.now(),
                    TradeEpisode.Status.FILLED, opp.netProfitRatio, result.irtSpent(), result.irtReceived(), 0,
                    result.legs, java.util.List.of(), ""));
            return;
        }

        Map<String, Double> stranded = result.strandedInventory();
        if (stranded.isEmpty()) {
            TradeEpisode.Status status = result.irtSpent() > 0
                    ? TradeEpisode.Status.ABORTED_RECOVERED   // spent rial, nothing stranded: got rial back already
                    : TradeEpisode.Status.ABORTED_NOTHING_SPENT;
            reporter.record(new TradeEpisode(episodeId, cfg.mode.name(), opp.triangle, startedAt, Instant.now(),
                    status, opp.netProfitRatio, result.irtSpent(), result.irtReceived(), 0, result.legs,
                    java.util.List.of(), result.legs.toString()));
            return;
        }

        if (recovery == null) {
            // Paper mode never strands inventory; reaching here would be an executor bug.
            log.error("episode {} stranded {} but no recovery manager is configured", episodeId, stranded);
            return;
        }
        pausedBases.add(opp.triangle.base);
        recovery.schedule(episodeId, startedAt, result);
    }

    /** RecoveryManager callback: report the finished episode and un-pause its base coin. */
    public void onRecoveredEpisode(TradeEpisode ep) {
        try {
            reporter.record(ep);
        } finally {
            pausedBases.remove(ep.triangle.base);
        }
    }

    public void shutdown() {
        worker.shutdownNow();
        reporter.logSummary();
    }
}
