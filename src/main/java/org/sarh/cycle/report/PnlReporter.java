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
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes one CSV row per completed {@link TradeEpisode} and keeps running per-path totals.
 *
 * <p>An episode is only recorded once the account is back in rial -- a closed loop immediately, an
 * aborted loop after its stranded inventory has been unwound -- so every row's P&amp;L is realised,
 * not marked.
 */
public final class PnlReporter {
    private static final Logger log = LoggerFactory.getLogger(PnlReporter.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_INSTANT;

    private static final String HEADER = String.join(",",
            "episode_id", "mode", "started_at", "finished_at", "triangle", "path", "status",
            "expected_pct", "irt_spent", "irt_received", "irt_recovered", "pnl_irt", "pnl_pct",
            "leg1", "leg2", "leg3", "recovery", "note");

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
        PathStats s = byPath.computeIfAbsent(ep.triangle.id(), k -> new PathStats());
        synchronized (s) {
            s.episodes++;
            if (ep.pnlIrt() > 0) s.wins++;
            if (ep.status != TradeEpisode.Status.FILLED) s.aborted++;
            s.pnlIrt += ep.pnlIrt();
            s.spentIrt += ep.irtSpent;
        }

        if (ep.status == TradeEpisode.Status.FILLED) {
            log.info("PNL {}", ep);
        } else {
            log.warn("PNL {} | recovery: {}", ep, ep.recoveryLegs);
        }
        appendRow(ep);
    }

    public void logSummary() {
        if (byPath.isEmpty()) {
            log.info("PNL SUMMARY: no completed episodes yet");
            return;
        }
        double total = 0, spent = 0;
        int episodes = 0, wins = 0, aborted = 0;
        StringBuilder sb = new StringBuilder("PNL SUMMARY by path\n");
        for (Map.Entry<String, PathStats> e : new TreeMap<>(byPath).entrySet()) {
            PathStats s = e.getValue();
            synchronized (s) {
                sb.append(String.format(Locale.ROOT, "  %-18s n=%-4d wins=%-4d aborted=%-3d pnl=%,15.0f IRT  (%.3f%% of spent)%n",
                        e.getKey(), s.episodes, s.wins, s.aborted, s.pnlIrt,
                        s.spentIrt > 0 ? s.pnlIrt / s.spentIrt * 100 : 0));
                total += s.pnlIrt;
                spent += s.spentIrt;
                episodes += s.episodes;
                wins += s.wins;
                aborted += s.aborted;
            }
        }
        sb.append(String.format(Locale.ROOT, "  %-18s n=%-4d wins=%-4d aborted=%-3d pnl=%,15.0f IRT  (%.3f%% of spent)",
                "TOTAL", episodes, wins, aborted, total, spent > 0 ? total / spent * 100 : 0));
        log.info(sb.toString());
    }

    private void appendRow(TradeEpisode ep) {
        String row = String.join(",",
                ep.id,
                ep.mode,
                TS.format(ep.startedAt),
                TS.format(ep.finishedAt),
                ep.triangle.id(),
                ep.triangle.path(),
                ep.status.name(),
                fmt(ep.expectedProfitRatio * 100, 4),
                fmt(ep.irtSpent, 0),
                fmt(ep.irtReceived, 0),
                fmt(ep.irtRecovered, 0),
                fmt(ep.pnlIrt(), 0),
                fmt(ep.pnlRatio() * 100, 4),
                legCell(ep.legs, 0),
                legCell(ep.legs, 1),
                legCell(ep.legs, 2),
                quote(ep.recoveryLegs.isEmpty() ? "" : ep.recoveryLegs.toString()),
                quote(ep.note));
        synchronized (fileLock) {
            try {
                if (csv.getParent() != null) Files.createDirectories(csv.getParent());
                boolean fresh = !Files.exists(csv) || Files.size(csv) == 0;
                String content = (fresh ? HEADER + "\n" : "") + row + "\n";
                Files.writeString(csv, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.error("could not append to {}: {}", csv, e.toString());
            }
        }
    }

    private static String legCell(java.util.List<LegResult> legs, int i) {
        if (i >= legs.size()) return "";
        LegResult r = legs.get(i);
        return quote(String.format(Locale.ROOT, "%s %s %s in=%s out=%s px=%s",
                r.leg.side, r.leg.market.symbol, r.status, fmt(r.amountIn, 8), fmt(r.amountOut, 8),
                fmt(r.avgPrice, 8)));
    }

    private static String fmt(double v, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
