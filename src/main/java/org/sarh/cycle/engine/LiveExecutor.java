package org.sarh.cycle.engine;

import com.fasterxml.jackson.databind.JsonNode;
import org.sarh.cycle.config.AppConfig;
import org.sarh.cycle.model.LegResult;
import org.sarh.cycle.model.Market;
import org.sarh.cycle.model.Opportunity;
import org.sarh.cycle.model.OrderBookTop;
import org.sarh.cycle.model.Side;
import org.sarh.cycle.model.Triangle;
import org.sarh.cycle.model.TriangleExecutionResult;
import org.sarh.cycle.model.TriangleLeg;
import org.sarh.cycle.rest.NobitexRestClient;
import org.sarh.cycle.ws.OrderBookCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Places real orders, one leg at a time, in triangle order.
 *
 * <p>This is NOT atomic: the three legs hit three independent order books over three separate
 * HTTP round trips. Between legs the market can move against the remaining path, and a leg can
 * fail to fill at all -- both leave the account holding an intermediate currency instead of
 * completing the loop. Every design choice here is about shrinking or bounding that window:
 * <ul>
 *   <li>Legs are limit orders priced {@code cross_by} through the touch: they fill immediately
 *       like a market order but cannot walk a thin book further than that.</li>
 *   <li>A leg that has not filled inside {@code leg_timeout_s} is cancelled, its partial fill is
 *       recorded exactly, and the remaining legs are skipped.</li>
 *   <li>Each leg is sized from the previous leg's <em>reported</em> net proceeds, never from the
 *       detector's estimate, so an order can never ask for more than the wallet holds.</li>
 *   <li>Nothing is ever retried on a transport error: a retried order that actually landed the
 *       first time doubles the position. Failures are reported and handed to recovery.</li>
 * </ul>
 */
public final class LiveExecutor implements Executor {
    private static final Logger log = LoggerFactory.getLogger(LiveExecutor.class);
    private static final long POLL_INTERVAL_MS = 400;

    private final NobitexRestClient rest;
    private final AppConfig cfg;
    private final OrderBookCache books;
    private final Deque<Long> recentOrderTimestamps = new ArrayDeque<>();

    public LiveExecutor(NobitexRestClient rest, AppConfig cfg, OrderBookCache books) {
        this.rest = rest;
        this.cfg = cfg;
        this.books = books;
    }

    @Override
    public TriangleExecutionResult execute(Opportunity opp) {
        List<LegResult> results = new ArrayList<>(3);
        double amountIn = opp.startAmountIrt; // every triangle's first leg spends rial

        double freeRial;
        try {
            freeRial = rest.freeBalance(Triangle.RIAL);
        } catch (Exception e) {
            return allSkipped(opp, "could not read rial balance: " + e.getMessage());
        }
        if (freeRial < amountIn) {
            return allSkipped(opp, String.format("free rial %.0f < notional %.0f", freeRial, amountIn));
        }

        boolean aborted = false;
        for (int i = 0; i < opp.triangle.legs.size(); i++) {
            TriangleLeg leg = opp.triangle.legs.get(i);
            if (aborted) {
                results.add(LegResult.skipped(leg, amountIn, "earlier leg did not complete"));
                continue;
            }
            OrderBookTop top = books.get(leg.market.symbol);
            int decimals = top == null ? 0 : top.priceDecimals;
            LegResult r = executeLeg(leg, amountIn, opp.legPrices[i], decimals);
            results.add(r);
            if (!r.completed()) {
                aborted = true;
                log.error("TRIANGLE {} ABORTED at leg {} ({}): {}", opp.triangle.id(), i + 1, leg, r);
            }
            amountIn = r.amountOut;
        }
        return new TriangleExecutionResult(opp, results);
    }

    private static TriangleExecutionResult allSkipped(Opportunity opp, String note) {
        List<LegResult> results = new ArrayList<>(3);
        double amountIn = opp.startAmountIrt;
        for (TriangleLeg leg : opp.triangle.legs) {
            results.add(LegResult.skipped(leg, amountIn, note));
            amountIn = 0;
        }
        log.warn("TRIANGLE {} not started: {}", opp.triangle.id(), note);
        return new TriangleExecutionResult(opp, results);
    }

    /** Sells {@code amount} of {@code irtMarket.src} back to rial at the current bid. Used by recovery. */
    public LegResult sellToRial(Market irtMarket, double amount) {
        TriangleLeg leg = new TriangleLeg(irtMarket, Side.SELL);
        OrderBookTop top = books.get(irtMarket.symbol);
        if (top == null || top.isStale(System.currentTimeMillis(), cfg.maxBookAgeMillis) || !top.isSane()) {
            return LegResult.failed(leg, amount, "no live book for " + irtMarket.symbol);
        }
        return executeLeg(leg, amount, top.bestBid, top.priceDecimals);
    }

    // -- one leg -----------------------------------------------------------------

    private LegResult executeLeg(TriangleLeg leg, double amountIn, double referencePrice, int priceDecimals) {
        if (!(amountIn > 0)) {
            return LegResult.failed(leg, amountIn, "nothing to convert");
        }
        try {
            takeOrderSlot();
        } catch (IllegalStateException e) {
            return LegResult.failed(leg, amountIn, e.getMessage());
        }

        // Limit price through the touch. Round in the aggressive direction to the market's own
        // precision so the order is never rejected for a sub-tick price.
        BigDecimal limitPrice = leg.side == Side.BUY
                ? round(referencePrice * (1 + cfg.crossBy), priceDecimals, RoundingMode.CEILING)
                : round(referencePrice * (1 - cfg.crossBy), priceDecimals, RoundingMode.FLOOR);
        if (limitPrice.signum() <= 0) {
            return LegResult.failed(leg, amountIn, "limit price rounded to zero");
        }

        // `amount` is always in src units. Size a BUY against the LIMIT price, not the touch, so
        // that even a fill at the very worst allowed price cannot cost more than amountIn.
        double rawSrcAmount = leg.side == Side.BUY ? amountIn / limitPrice.doubleValue() : amountIn;
        BigDecimal amount = roundDown(rawSrcAmount, leg.market.amountStep);
        if (amount.signum() <= 0) {
            return LegResult.failed(leg, amountIn, "amount rounds to zero at step " + leg.market.amountStep);
        }

        double notionalDst = amount.doubleValue() * referencePrice;
        if (leg.isRialQuoted() && notionalDst < cfg.minOrderRial) {
            return LegResult.failed(leg, amountIn, String.format(
                    "notional %.0f rial below exchange minimum %.0f", notionalDst, cfg.minOrderRial));
        }
        if (!leg.isRialQuoted() && notionalDst < cfg.minOrderUsdt) {
            return LegResult.failed(leg, amountIn, String.format(
                    "notional %.4f USDT below exchange minimum %.2f", notionalDst, cfg.minOrderUsdt));
        }

        String clientOrderId = "tri" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        NobitexRestClient.OrderReceipt receipt;
        try {
            receipt = rest.addOrder(leg.market.src, leg.market.dst, leg.side, amount, limitPrice, clientOrderId);
        } catch (Exception e) {
            return LegResult.failed(leg, amountIn, "order rejected: " + e.getMessage());
        }
        log.info("placed {} {} amount={} limit={} cid={} id={}", leg.side, leg.market.symbol, amount,
                limitPrice, clientOrderId, receipt.id());

        JsonNode finalOrder = awaitTerminal(receipt.id(), clientOrderId);
        if (finalOrder == null) {
            return LegResult.failed(leg, amountIn, "order " + clientOrderId + " state unknown after timeout -- "
                    + "check the exchange before touching this account");
        }

        double matched = finalOrder.path("matchedAmount").asDouble(0);
        if (matched <= 0) {
            return LegResult.failed(leg, amountIn, "order " + clientOrderId + " matched nothing ("
                    + finalOrder.path("status").asText("?") + ")");
        }
        double avgPrice = finalOrder.path("averagePrice").asDouble(0);
        if (!(avgPrice > 0)) avgPrice = limitPrice.doubleValue();

        double deviation = Math.abs(avgPrice - referencePrice) / referencePrice;
        if (deviation > cfg.maxLegSlippage) {
            log.warn("{} filled {} away from the priced-in rate ({} vs {})", leg,
                    String.format("%.3f%%", deviation * 100), avgPrice, referencePrice);
        }

        // Nobitex charges the fee on the asset received: src for a buy, dst for a sell. Take the
        // larger of the reported fee and the configured rate, so the next leg is sized against a
        // wallet balance that is at least what we assume -- under-estimating leaves dust, over-
        // estimating gets an order rejected for insufficient balance.
        double feeRate = leg.isRialQuoted() ? cfg.takerFeeIrt : cfg.takerFeeUsdt;
        double reportedFee = finalOrder.path("fee").asDouble(0);
        double consumed, grossOut;
        if (leg.side == Side.BUY) {
            consumed = matched * avgPrice;   // dst spent
            grossOut = matched;              // src received before fee
        } else {
            consumed = matched;              // src sold
            grossOut = matched * avgPrice;   // dst received before fee
        }
        double fee = Math.max(reportedFee > 0 && reportedFee < grossOut * 0.05 ? reportedFee : 0, grossOut * feeRate);
        double amountOut = Math.max(0, grossOut - fee);
        consumed = Math.min(consumed, amountIn);

        return LegResult.filled(leg, amountIn, consumed, amountOut, avgPrice, receipt.id());
    }

    /**
     * Polls until the order is done/cancelled/rejected or the leg timeout passes; on timeout the
     * order is cancelled and its final state re-read so a partial fill is captured exactly.
     * Returns null only if the exchange could not be reached to establish the final state.
     */
    private JsonNode awaitTerminal(Long orderId, String clientOrderId) {
        long deadline = System.currentTimeMillis() + cfg.legTimeoutS * 1000L;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = safeStatus(orderId, clientOrderId);
            if (last != null && isTerminal(last)) return last;
            sleep(POLL_INTERVAL_MS);
        }
        try {
            rest.cancelOrder(orderId, clientOrderId);
        } catch (Exception e) {
            log.warn("cancel of {} failed: {}", clientOrderId, e.toString());
        }
        // Give the cancel a moment to settle, then read the final state (partial fills included).
        for (int i = 0; i < 5; i++) {
            sleep(POLL_INTERVAL_MS);
            JsonNode s = safeStatus(orderId, clientOrderId);
            if (s != null) {
                last = s;
                if (isTerminal(s)) return s;
            }
        }
        return last;
    }

    private static boolean isTerminal(JsonNode order) {
        String status = order.path("status").asText("").toLowerCase();
        return status.equals("done") || status.equals("canceled") || status.equals("rejected");
    }

    private JsonNode safeStatus(Long orderId, String clientOrderId) {
        try {
            // Numeric id first: a filled order is no longer "open", and clientOrderId lookups only
            // search open orders.
            JsonNode order = rest.orderStatus(orderId, orderId == null ? clientOrderId : null);
            return order.isMissingNode() || order.isNull() ? null : order;
        } catch (Exception e) {
            log.debug("status poll failed for {}: {}", clientOrderId, e.toString());
            return null;
        }
    }

    private void takeOrderSlot() {
        synchronized (recentOrderTimestamps) {
            long now = System.currentTimeMillis();
            while (!recentOrderTimestamps.isEmpty() && now - recentOrderTimestamps.peekFirst() > 600_000) {
                recentOrderTimestamps.pollFirst();
            }
            if (recentOrderTimestamps.size() >= cfg.maxOrdersPer10Min) {
                throw new IllegalStateException("local order-rate budget (" + cfg.maxOrdersPer10Min
                        + "/10min) exhausted; refusing to place another order");
            }
            recentOrderTimestamps.addLast(now);
        }
    }

    private static BigDecimal round(double value, int decimals, RoundingMode mode) {
        return BigDecimal.valueOf(value).setScale(Math.max(0, decimals), mode);
    }

    private static BigDecimal roundDown(double value, double step) {
        if (!(step > 0)) return BigDecimal.valueOf(value);
        BigDecimal v = BigDecimal.valueOf(value);
        BigDecimal s = BigDecimal.valueOf(step);
        return v.divide(s, 0, RoundingMode.FLOOR).multiply(s);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
