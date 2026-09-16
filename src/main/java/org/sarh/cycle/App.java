package org.sarh.cycle;

import org.sarh.cycle.config.AppConfig;
import org.sarh.cycle.engine.ArbitrageDetector;
import org.sarh.cycle.engine.Executor;
import org.sarh.cycle.engine.LiveExecutor;
import org.sarh.cycle.engine.PaperExecutor;
import org.sarh.cycle.engine.RecoveryManager;
import org.sarh.cycle.engine.TradeCoordinator;
import org.sarh.cycle.engine.TriangleBuilder;
import org.sarh.cycle.market.MarketDiscovery;
import org.sarh.cycle.model.Triangle;
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
            log.error("no base coins survived discovery/filtering -- check universe.* in config.yaml");
            return;
        }
        List<Triangle> triangles = TriangleBuilder.build(universe);
        log.info("built {} triangles across {} base coins", triangles.size(), universe.bases.size());

        if (cfg.mode == AppConfig.Mode.LIVE) {
            double rial = rest.freeBalance(Triangle.RIAL);
            log.warn("LIVE MODE: real orders will be placed. Free rial balance: {}. Notional per triangle: {}. "
                    + "Ctrl+C within 10s to abort.", String.format("%,.0f", rial), String.format("%,.0f", cfg.tradeNotionalIrt));
            Thread.sleep(10_000);
        }

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scheduler");
            t.setDaemon(true);
            return t;
        });
        PnlReporter reporter = new PnlReporter(cfg.reportCsvPath);

        // The detector runs on the websocket thread and needs the coordinator; the coordinator needs
        // the executor; the live executor and recovery need the book cache. Wire back-references
        // through holders so construction order can follow the data flow.
        ArbitrageDetector[] detectorRef = new ArbitrageDetector[1];
        TradeCoordinator[] coordinatorRef = new TradeCoordinator[1];
        OrderBookCache books = new OrderBookCache(symbol -> detectorRef[0].onBookUpdate(symbol));

        Executor executor;
        RecoveryManager recovery = null;
        if (cfg.mode == AppConfig.Mode.LIVE) {
            LiveExecutor live = new LiveExecutor(rest, cfg, books);
            executor = live;
            recovery = new RecoveryManager(cfg, universe, live, rest, scheduler,
                    ep -> coordinatorRef[0].onRecoveredEpisode(ep));
        } else {
            executor = new PaperExecutor();
        }
        TradeCoordinator coordinator = new TradeCoordinator(cfg, executor, reporter, recovery, scheduler);
        coordinatorRef[0] = coordinator;
        detectorRef[0] = new ArbitrageDetector(cfg, books, triangles, coordinator::onOpportunity);

        List<String> channels = new ArrayList<>();
        channels.add("public:orderbook-" + universe.usdtIrt.symbol);
        for (String base : universe.bases) {
            channels.add("public:orderbook-" + universe.irtBySymbolBase.get(base).symbol);
            channels.add("public:orderbook-" + universe.usdtBySymbolBase.get(base).symbol);
        }
        log.info("subscribing to {} orderbook channels; P&L rows go to {}", channels.size(), cfg.reportCsvPath);

        CentrifugoWebSocketClient ws = new CentrifugoWebSocketClient(cfg.wsUrl, channels, books::onOrderbookPush);
        ws.start();

        scheduler.scheduleAtFixedRate(() -> log.info("feed: {} book updates so far across {} of {} symbols",
                books.updateCount(), books.symbolCount(), channels.size()), 60, 60, TimeUnit.SECONDS);

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutting down");
            ws.stop();
            coordinator.shutdown();
            scheduler.shutdownNow();
            shutdown.countDown();
        }));
        shutdown.await();
    }
}
