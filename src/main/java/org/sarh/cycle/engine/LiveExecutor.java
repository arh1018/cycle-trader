package org.sarh.cycle.engine;

import com.fasterxml.jackson.databind.JsonNode;

import org.sarh.cycle.config.AppConfig;
import org.sarh.cycle.model.CycleExecutionResult;
import org.sarh.cycle.model.CycleLeg;
import org.sarh.cycle.model.LegResult;
import org.sarh.cycle.model.Market;
import org.sarh.cycle.model.Opportunity;
import org.sarh.cycle.model.OrderBook;
import org.sarh.cycle.model.Side;
import org.sarh.cycle.rest.NobitexApiException;
import org.sarh.cycle.rest.NobitexRestClient;
import org.sarh.cycle.ws.OrderBookCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Places real orders, one leg at a time.
 *
 * <p>The two legs are not atomic: between them the book can move and leg 2 can be refused, leaving
 * the coin in the wallet. Everything here is about bounding that:
 *
 * <ul>
 *   <li>The limit price sits {@code cross_by} beyond the deepest level the detector's walk needed,
 *       so the order fills immediately for the whole size but cannot chase a moving book.
 *   <li>Amounts are rounded to the coarser of the options precision and the size precision the
 *       exchange itself prints in the book -- Nobitex quantises some coins to whole units.
 *   <li>Leg 2 is sized from leg 1's <em>reported</em> net proceeds, never the detector's estimate.
 *   <li>An explicit rejection right after a fill (the just-bought balance not spendable yet) is
 *       retried after a pause with the balance re-read; a transport error is never retried, because
 *       the order may have landed.
 *   <li>A leg that produced anything continues the cycle; Nobitex closes orders as Done once the
 *       remainder is dust, so matched &lt; ordered is normal.
 *   <li>A leg unfilled after {@code leg_timeout_s} is cancelled and its final state re-read so a
 *       partial fill is accounted exactly.
 * </ul>
 */
public final class LiveExecutor implements Executor {
    private static final Logger log = LoggerFactory.getLogger(LiveExecutor.class);
    private static final long POLL_INTERVAL_MS = 400;

    private final NobitexRestClient rest;
    private final AppConfig cfg;
    private final OrderBookCache books;
    private final Inventory inventory;
    private final Deque<Long> recentOrderTimestamps = new ArrayDeque<>();

    public LiveExecutor(
            NobitexRestClient rest, AppConfig cfg, OrderBookCache books, Inventory inventory) {
        this.rest = rest;
        this.cfg = cfg;
        this.books = books;
        this.inventory = inventory;
    }

    @Override
    public CycleExecutionResult execute(Opportunity opp) {
        CycleLeg leg1 = opp.cycle.legs.get(0), leg2 = opp.cycle.legs.get(1);
        LegResult r1 = executeLeg(leg1, opp.startAmount, opp.legMarginal[0]);
        if (!r1.producedAnything()) {
            log.error("CYCLE {} aborted at leg 1: {}", opp.cycle.id(), r1);
            return new CycleExecutionResult(
                    opp, List.of(r1, LegResult.skipped(leg2, 0, "leg 1 produced nothing")));
        }
        LegResult r2 = executeLeg(leg2, r1.amountOut, opp.legMarginal[1]);
        if (!r2.producedAnything()) {
            log.error(
                    "CYCLE {} aborted at leg 2: {} -- {} {} stranded",
                    opp.cycle.id(),
                    r2,
                    r1.amountOut,
                    opp.cycle.midCurrency());
        }
        return new CycleExecutionResult(opp, List.of(r1, r2));
    }

    /**
     * Sell or buy against the freshest book this method can get, for recovery and rebalancing --
     * called minutes after the websocket subscribed, on coins thin enough that the cached top can
     * be many minutes stale. The normal {@code max_book_age_s} gate is right for deciding whether
     * to <em>enter</em> a cycle; it is wrong here, where a stale-but-real price is far better than
     * refusing to sell at all. A market truly missing from the cache (never pushed, or the symbol
     * is wrong) still fails with "no live book", now only after a REST attempt.
     */
    @Override
    public LegResult sell(Market market, double srcAmount) {
        CycleLeg leg = new CycleLeg(market, Side.SELL);
        OrderBook book = freshOrRefreshedBook(market.symbol);
        if (book == null) {
            return LegResult.failed(leg, srcAmount, "no live book for " + market.symbol);
        }
        OrderBook.Walk w = book.sellAmount(srcAmount);
        return executeLeg(leg, srcAmount, w.srcAmount() > 0 ? w.marginal() : book.bestBid());
    }

    @Override
    public LegResult buy(Market market, double dstBudget) {
        CycleLeg leg = new CycleLeg(market, Side.BUY);
        OrderBook book = freshOrRefreshedBook(market.symbol);
        if (book == null) {
            return LegResult.failed(leg, dstBudget, "no live book for " + market.symbol);
        }
        OrderBook.Walk w = book.buyWithBudget(dstBudget);
        return executeLeg(leg, dstBudget, w.srcAmount() > 0 ? w.marginal() : book.bestAsk());
    }

    private OrderBook freshOrRefreshedBook(String symbol) {
        OrderBook cached = books.get(symbol);
        long now = System.currentTimeMillis();
        boolean usable =
                cached != null && !cached.isStale(now, cfg.maxBookAgeMillis) && cached.isSane();
        if (usable) {
            return cached;
        }
        try {
            books.onOrderbookPush("public:orderbook-" + symbol, rest.orderbookOne(symbol));
        } catch (Exception e) {
            log.warn("REST refresh of {} failed: {}", symbol, e.toString());
        }
        OrderBook refreshed = books.get(symbol);
        return refreshed != null && refreshed.isSane() ? refreshed : null;
    }

    // -- one leg -----------------------------------------------------------------

    private LegResult executeLeg(CycleLeg leg, double amountIn, double referencePrice) {
        if (!(amountIn > 0)) {
            return LegResult.failed(leg, amountIn, "nothing to convert");
        }
        if (!(referencePrice > 0)) {
            return LegResult.failed(leg, amountIn, "no reference price");
        }
        OrderBook book = books.get(leg.market.symbol);
        int priceDecimals = book == null ? 0 : book.priceDecimals;
        double step =
                Math.max(
                        leg.market.amountStep, book == null ? 0 : Math.pow(10, -book.sizeDecimals));

        BigDecimal limitPrice =
                leg.side == Side.BUY
                        ? round(
                                referencePrice * (1 + cfg.crossBy),
                                priceDecimals,
                                RoundingMode.CEILING)
                        : round(
                                referencePrice * (1 - cfg.crossBy),
                                priceDecimals,
                                RoundingMode.FLOOR);
        if (limitPrice.signum() <= 0) {
            return LegResult.failed(leg, amountIn, "limit price rounded to zero");
        }

        // `amount` is always in src units. A BUY is sized against the LIMIT price so that a fill at
        // the worst allowed price still costs no more than amountIn.
        double budget = amountIn;
        BigDecimal amount = srcAmount(leg, budget, limitPrice, step);
        if (amount.signum() <= 0) {
            return LegResult.failed(leg, amountIn, "amount rounds to zero at step " + step);
        }

        double notionalDst = amount.doubleValue() * referencePrice;
        double minimum = leg.isRialQuoted() ? cfg.minOrderRial : cfg.minOrderUsdt;
        if (notionalDst < minimum) {
            return LegResult.failed(
                    leg,
                    amountIn,
                    String.format(
                            "notional %.4f %s below exchange minimum %.2f",
                            notionalDst, leg.market.dst, minimum));
        }

        NobitexRestClient.OrderReceipt receipt = null;
        String clientOrderId = null;
        for (int attempt = 0; attempt <= cfg.rejectRetries; attempt++) {
            try {
                takeOrderSlot();
            } catch (IllegalStateException e) {
                return LegResult.failed(leg, amountIn, e.getMessage());
            }
            clientOrderId = "cyc" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            try {
                receipt =
                        rest.addOrder(
                                leg.market.src,
                                leg.market.dst,
                                leg.side,
                                amount,
                                limitPrice,
                                clientOrderId);
                break;
            } catch (NobitexApiException e) {
                // Explicit rejection: the order does not exist, so re-submitting cannot
                // double-fill.
                if (attempt == cfg.rejectRetries) {
                    return LegResult.failed(
                            leg,
                            amountIn,
                            "order rejected after "
                                    + (attempt + 1)
                                    + " attempts: "
                                    + e.getMessage());
                }
                log.warn(
                        "{} rejected ({}); re-reading balance and retrying in {} ms",
                        leg,
                        e.getMessage(),
                        cfg.rejectRetryWaitMs);
                sleep(cfg.rejectRetryWaitMs);
                double free = safeFreeBalance(leg.inputCurrency());
                if (free > 0 && free < budget) {
                    budget = free;
                    amount = srcAmount(leg, budget, limitPrice, step);
                    if (amount.signum() <= 0) {
                        return LegResult.failed(leg, amountIn, "balance re-read as " + free);
                    }
                }
            } catch (Exception e) {
                return LegResult.failed(
                        leg,
                        amountIn,
                        "order submission failed (NOT retried, may have landed): "
                                + e.getMessage());
            }
        }
        inventory.markTrade();
        log.info(
                "placed {} {} amount={} limit={} cid={} id={}",
                leg.side,
                leg.market.symbol,
                amount,
                limitPrice,
                clientOrderId,
                receipt.id());

        JsonNode finalOrder = awaitTerminal(receipt.id(), clientOrderId);
        if (finalOrder == null) {
            return LegResult.failed(
                    leg,
                    amountIn,
                    "order "
                            + clientOrderId
                            + " state unknown after timeout -- "
                            + "check the exchange before touching this account");
        }

        double matched = finalOrder.path("matchedAmount").asDouble(0);
        if (matched <= 0) {
            return LegResult.failed(
                    leg,
                    amountIn,
                    "order "
                            + clientOrderId
                            + " matched nothing ("
                            + finalOrder.path("status").asText("?")
                            + ")");
        }
        double avgPrice = finalOrder.path("averagePrice").asDouble(0);
        if (!(avgPrice > 0)) {
            avgPrice = limitPrice.doubleValue();
        }

        double deviation = Math.abs(avgPrice - referencePrice) / referencePrice;
        if (deviation > cfg.maxLegSlippage) {
            log.warn(
                    "{} filled {} away from the priced-in rate ({} vs {})",
                    leg,
                    String.format("%.3f%%", deviation * 100),
                    avgPrice,
                    referencePrice);
        }

        // Fee is charged on the asset received: src for a buy, dst for a sell. Use the larger of
        // the
        // reported fee and the configured rate: under-estimating proceeds leaves dust, over-
        // estimating gets the next order rejected for insufficient balance.
        double feeRate = leg.isRialQuoted() ? cfg.takerFeeIrt : cfg.takerFeeUsdt;
        double reportedFee = finalOrder.path("fee").asDouble(0);
        double consumed = leg.side == Side.BUY ? matched * avgPrice : matched;
        double grossOut = leg.side == Side.BUY ? matched : matched * avgPrice;
        double fee =
                Math.max(
                        reportedFee > 0 && reportedFee < grossOut * 0.05 ? reportedFee : 0,
                        grossOut * feeRate);
        double amountOut = Math.max(0, grossOut - fee);
        consumed = Math.min(consumed, amountIn);

        inventory.applyFill(leg.inputCurrency(), consumed, leg.outputCurrency(), amountOut);
        return LegResult.filled(
                leg,
                amountIn,
                amount.doubleValue(),
                matched,
                consumed,
                amountOut,
                avgPrice,
                receipt.id());
    }

    private static BigDecimal srcAmount(
            CycleLeg leg, double budget, BigDecimal limitPrice, double step) {
        double raw = leg.side == Side.BUY ? budget / limitPrice.doubleValue() : budget;
        return roundDown(raw, step);
    }

    private double safeFreeBalance(String currency) {
        try {
            return rest.freeBalance(currency);
        } catch (Exception e) {
            log.debug("balance read failed for {}: {}", currency, e.toString());
            return -1;
        }
    }

    /**
     * Polls until the order is done/cancelled/rejected or the leg timeout passes; on timeout the
     * order is cancelled and its final state re-read so a partial fill is captured exactly. Returns
     * null only if the exchange could not be reached to establish the final state.
     */
    private JsonNode awaitTerminal(Long orderId, String clientOrderId) {
        long deadline = System.currentTimeMillis() + cfg.legTimeoutS * 1000L;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = safeStatus(orderId, clientOrderId);
            if (last != null && isTerminal(last)) {
                return last;
            }
            sleep(POLL_INTERVAL_MS);
        }
        try {
            rest.cancelOrder(orderId, clientOrderId);
        } catch (Exception e) {
            log.warn("cancel of {} failed: {}", clientOrderId, e.toString());
        }
        for (int i = 0; i < 5; i++) {
            sleep(POLL_INTERVAL_MS);
            JsonNode s = safeStatus(orderId, clientOrderId);
            if (s != null) {
                last = s;
                if (isTerminal(s)) {
                    return s;
                }
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
            while (!recentOrderTimestamps.isEmpty()
                    && now - recentOrderTimestamps.peekFirst() > 600_000) {
                recentOrderTimestamps.pollFirst();
            }
            if (recentOrderTimestamps.size() >= cfg.maxOrdersPer10Min) {
                throw new IllegalStateException(
                        "local order-rate budget ("
                                + cfg.maxOrdersPer10Min
                                + "/10min) exhausted; refusing to place another order");
            }
            recentOrderTimestamps.addLast(now);
        }
    }

    private static BigDecimal round(double value, int decimals, RoundingMode mode) {
        return BigDecimal.valueOf(value).setScale(Math.max(0, decimals), mode);
    }

    private static BigDecimal roundDown(double value, double step) {
        if (!(step > 0)) {
            return BigDecimal.valueOf(value);
        }
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
