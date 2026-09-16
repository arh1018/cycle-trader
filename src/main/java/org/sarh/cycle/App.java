package org.sarh.cycle;

import org.sarh.cycle.config.AppConfig;
import org.sarh.cycle.engine.ArbitrageDetector;
import org.sarh.cycle.engine.CycleBuilder;
import org.sarh.cycle.engine.Executor;
import org.sarh.cycle.engine.Inventory;
import org.sarh.cycle.engine.LiveExecutor;
import org.sarh.cycle.engine.PaperExecutor;
import org.sarh.cycle.engine.TradeCoordinator;
import org.sarh.cycle.market.MarketDiscovery;
import org.sarh.cycle.model.Cycle;
import org.sarh.cycle.report.PnlReporter;
import org.sarh.cycle.rest.NobitexAuth;
import org.sarh.cycle.rest.NobitexRestClient;
import org.sarh.cycle.ws.CentrifugoWebSocketClient;
import org.sarh.cycle.ws.OrderBookCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of(args.length > 0 ? args[0] : "config/config.yaml");
        AppConfig cfg = AppConfig.load(configPath);
        log.info("starting cycle-trader in {} mode (config: {})", cfg.mode, configPath);

        NobitexAuth auth = null;
        if (cfg.apiKey != null && !cfg.apiKey.isBlank()) {
            auth = new NobitexAuth(cfg.apiKey, cfg.apiSecret);
        }
        NobitexRestClient rest = new NobitexRestClient(cfg.restUrl, auth);

        MarketDiscovery.Universe universe = new MarketDiscovery(rest, cfg).discover();
        if (universe.bases.isEmpty()) {
            log.error(
                    "no base coins survived discovery/filtering -- check universe.* in"
                            + " config.yaml");
            return;
        }
        List<Cycle> cycles = CycleBuilder.build(universe);
        log.info("built {} cycles across {} base coins", cycles.size(), universe.bases.size());

        Inventory inventory = new Inventory();
        if (rest.isAuthenticated()) {
            Map<String, Double> wallet = rest.allFreeBalances();
            wallet.forEach(inventory::set);
        } else {
            inventory.set(Cycle.RIAL, cfg.paperStartIrt);
            inventory.set(Cycle.USDT, cfg.paperStartUsdt);
        }
        log.info("{}", inventory.describe(0));

        if (cfg.mode == AppConfig.Mode.LIVE) {
            log.warn(
                    "LIVE MODE: real orders will be placed. Notional per cycle: {} IRT. "
                            + "Stop with SIGTERM (scripts/stop.sh). Starting in 10s.",
                    String.format("%,.0f", cfg.tradeNotionalIrt));
            Thread.sleep(10_000);
        }

        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "scheduler");
                            t.setDaemon(true);
                            return t;
                        });
        PnlReporter reporter = new PnlReporter(cfg.reportCsvPath);

        // The detector runs on the websocket thread and needs the coordinator; the coordinator
        // needs the executor; both executors need the book cache. Wire the back-reference through
        // a holder so construction can follow the data flow.
        ArbitrageDetector[] detectorRef = new ArbitrageDetector[1];
        OrderBookCache books =
                new OrderBookCache(
                        cfg.maxDepthLevels, symbol -> detectorRef[0].onBookUpdate(symbol));

        Executor executor =
                cfg.mode == AppConfig.Mode.LIVE
                        ? new LiveExecutor(rest, cfg, books, inventory)
                        : new PaperExecutor(cfg, books, inventory);
        TradeCoordinator coordinator =
                new TradeCoordinator(
                        cfg, executor, inventory, books, universe, reporter, scheduler);
        detectorRef[0] =
                new ArbitrageDetector(
                        cfg, books, universe.usdtIrt.symbol, cycles, coordinator::onOpportunity);

        List<String> channels = new ArrayList<>();
        channels.add("public:orderbook-" + universe.usdtIrt.symbol);
        for (String base : universe.bases) {
            channels.add("public:orderbook-" + universe.irtBySymbolBase.get(base).symbol);
            channels.add("public:orderbook-" + universe.usdtBySymbolBase.get(base).symbol);
        }
        log.info(
                "subscribing to {} orderbook channels; P&L rows go to {}",
                channels.size(),
                cfg.reportCsvPath);

        CentrifugoWebSocketClient ws =
                new CentrifugoWebSocketClient(cfg.wsUrl, channels, books::onOrderbookPush);
        ws.start();

        scheduler.scheduleAtFixedRate(
                () ->
                        log.info(
                                "feed: {} book updates so far across {} of {} symbols",
                                books.updateCount(),
                                books.symbolCount(),
                                channels.size()),
                60,
                60,
                TimeUnit.SECONDS);
        if (cfg.summaryIntervalS > 0) {
            scheduler.scheduleAtFixedRate(
                    () -> log.info(detectorRef[0].nearMissReport(10)),
                    cfg.summaryIntervalS,
                    cfg.summaryIntervalS,
                    TimeUnit.SECONDS);
        }
        if (rest.isAuthenticated() && cfg.reconcileIntervalS > 0) {
            scheduler.scheduleAtFixedRate(
                    () -> {
                        try {
                            boolean done = inventory.reconcile(v -> rest.allFreeBalances(), 30_000);
                            if (done) {
                                log.debug("inventory reconciled from wallet");
                            }
                        } catch (Exception e) {
                            log.warn("wallet reconcile failed: {}", e.toString());
                        }
                    },
                    cfg.reconcileIntervalS,
                    cfg.reconcileIntervalS,
                    TimeUnit.SECONDS);
        }
        if (cfg.mode == AppConfig.Mode.LIVE && cfg.sweepOnStart) {
            // Give the feed a moment to populate books before pricing the sweep.
            scheduler.schedule(coordinator::sweepStrays, 20, TimeUnit.SECONDS);
        }

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    log.info("shutting down");
                                    coordinator.halt(); // first: nothing new may start
                                    ws.stop();
                                    coordinator.shutdown();
                                    log.info(detectorRef[0].nearMissReport(10));
                                    scheduler.shutdownNow();
                                    shutdown.countDown();
                                },
                                "shutdown"));
        shutdown.await();
    }
}
