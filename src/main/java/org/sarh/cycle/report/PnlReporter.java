package org.sarh.cycle.report;

import org.sarh.cycle.model.LegResult;
import org.sarh.cycle.model.TradeEpisode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes one CSV row per completed {@link TradeEpisode} and keeps running per-path totals.
 *
 * <p>An episode is only recorded once its value is back in a quote currency -- a closed cycle
 * immediately, an aborted one after its stranded coin has been unwound -- so every row's P&amp;L is
 * realised, not marked.
 */
public final class PnlReporter {
    private static final Logger log = LoggerFactory.getLogger(PnlReporter.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_INSTANT;

    private static final String HEADER =
            String.join(
                    ",",
                    "episode_id",
                    "mode",
                    "kind",
                    "started_at",
                    "finished_at",
                    "label",
                    "path",
                    "status",
                    "expected_pct",
                    "start_ccy",
                    "start_amount",
                    "end_ccy",
                    "end_amount",
                    "recovered_amount",
                    "usdtirt_bid",
                    "usdtirt_ask",
                    "spent_irt",
                    "back_irt",
                    "pnl_irt",
                    "pnl_pct",
                    "leg1",
                    "leg2",
                    "recovery",
                    "note");

    private static final class PathStats {
        int episodes;
        int wins;
        int aborted;
        double pnlIrt;
        double spentIrt;
    }

    private final Path csv;
    private final Map<String, PathStats> byPath = new ConcurrentHashMap<>();
    private final Object fileLock = new Object();

    public PnlReporter(Path csv) {
        this.csv = csv;
    }

    public void record(TradeEpisode ep) {
        PathStats s = byPath.computeIfAbsent(ep.pathLabel, k -> new PathStats());
        synchronized (s) {
            s.episodes++;
            if (ep.pnlIrt() > 0) {
                s.wins++;
            }
            if (ep.status != TradeEpisode.Status.FILLED
                    && ep.status != TradeEpisode.Status.REBALANCED
                    && ep.status != TradeEpisode.Status.SWEPT) {
                s.aborted++;
            }
            s.pnlIrt += ep.pnlIrt();
            s.spentIrt += ep.spentValueIrt();
        }

        if (ep.status == TradeEpisode.Status.FILLED
                || ep.status == TradeEpisode.Status.REBALANCED) {
            log.info("PNL {}", ep);
        } else {
            log.warn("PNL {} | recovery: {} | {}", ep, ep.recoveryLegs, ep.note);
        }
        appendRow(ep);
    }

    public void logSummary() {
        if (byPath.isEmpty()) {
            log.info("PNL SUMMARY: no completed episodes yet");
            return;
        }
        double total = 0;
        double spent = 0;
        int episodes = 0;
        int wins = 0;
        int aborted = 0;
        StringBuilder sb = new StringBuilder("PNL SUMMARY by path\n");
        for (Map.Entry<String, PathStats> e : new TreeMap<>(byPath).entrySet()) {
            PathStats s = e.getValue();
            synchronized (s) {
                sb.append(
                        String.format(
                                Locale.ROOT,
                                "  %-22s n=%-4d wins=%-4d aborted=%-3d pnl=%,15.0f IRT  (%+.3f%% of"
                                        + " spent)%n",
                                e.getKey(),
                                s.episodes,
                                s.wins,
                                s.aborted,
                                s.pnlIrt,
                                s.spentIrt > 0 ? s.pnlIrt / s.spentIrt * 100 : 0));
                total += s.pnlIrt;
                spent += s.spentIrt;
                episodes += s.episodes;
                wins += s.wins;
                aborted += s.aborted;
            }
        }
        sb.append(
                String.format(
                        Locale.ROOT,
                        "  %-22s n=%-4d wins=%-4d aborted=%-3d pnl=%,15.0f IRT  (%+.3f%% of spent)",
                        "TOTAL",
                        episodes,
                        wins,
                        aborted,
                        total,
                        spent > 0 ? total / spent * 100 : 0));
        log.info(sb.toString());
    }

    private void appendRow(TradeEpisode ep) {
        String row =
                String.join(
                        ",",
                        ep.id,
                        ep.mode,
                        ep.kind.name(),
                        TS.format(ep.startedAt),
                        TS.format(ep.finishedAt),
                        quote(ep.pathLabel),
                        quote(ep.path),
                        ep.status.name(),
                        fmt(ep.expectedProfitRatio * 100, 4),
                        ep.startCurrency,
                        fmt(ep.startAmount, 8),
                        ep.endCurrency,
                        fmt(ep.endAmount, 8),
                        fmt(ep.recoveredAmount, 8),
                        fmt(ep.usdtIrtBid, 0),
                        fmt(ep.usdtIrtAsk, 0),
                        fmt(ep.spentValueIrt(), 0),
                        fmt(ep.backValueIrt(), 0),
                        fmt(ep.pnlIrt(), 0),
                        fmt(ep.pnlRatio() * 100, 4),
                        legCell(ep.legs, 0),
                        legCell(ep.legs, 1),
                        quote(ep.recoveryLegs.isEmpty() ? "" : ep.recoveryLegs.toString()),
                        quote(ep.note));
        synchronized (fileLock) {
            try {
                if (csv.getParent() != null) {
                    Files.createDirectories(csv.getParent());
                }
                boolean fresh = !Files.exists(csv) || Files.size(csv) == 0;
                String content = (fresh ? HEADER + "\n" : "") + row + "\n";
                Files.writeString(
                        csv,
                        content,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.error("could not append to {}: {}", csv, e.toString());
            }
        }
    }

    private static String legCell(List<LegResult> legs, int i) {
        if (i >= legs.size()) {
            return "";
        }
        LegResult r = legs.get(i);
        return quote(
                String.format(
                        Locale.ROOT,
                        "%s %s %s in=%s out=%s px=%s",
                        r.leg.side,
                        r.leg.market.symbol,
                        r.status,
                        fmt(r.amountIn, 8),
                        fmt(r.amountOut, 8),
                        fmt(r.avgPrice, 8)));
    }

    private static String fmt(double v, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
