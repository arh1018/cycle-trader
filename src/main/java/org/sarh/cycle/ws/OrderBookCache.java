package org.sarh.cycle.ws;

import com.fasterxml.jackson.databind.JsonNode;
import org.sarh.cycle.model.OrderBookTop;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Thread-safe best-bid/ask cache, fed by the websocket, read by the arbitrage engine. */
public final class OrderBookCache {
    private final ConcurrentHashMap<String, OrderBookTop> tops = new ConcurrentHashMap<>();
    private final AtomicLong updates = new AtomicLong();
    private final Consumer<String> onUpdate; // symbol that just changed

    public OrderBookCache(Consumer<String> onUpdate) {
        this.onUpdate = onUpdate;
    }

    public static String symbolFromChannel(String channel) {
        // "public:orderbook-BTCIRT" -> "BTCIRT"
        int dash = channel.lastIndexOf('-');
        return dash < 0 ? channel : channel.substring(dash + 1);
    }

    /** Handler suitable for {@code CentrifugoWebSocketClient}'s push callback. */
    public void onOrderbookPush(String channel, JsonNode payload) {
        String symbol = symbolFromChannel(channel);
        JsonNode bids = payload.path("bids");
        JsonNode asks = payload.path("asks");
        if (!bids.isArray() || bids.isEmpty() || !asks.isArray() || asks.isEmpty()) return;

        JsonNode bidPx = bids.get(0).get(0);
        JsonNode askPx = asks.get(0).get(0);
        if (bidPx == null || askPx == null) return;

        double bestBid = bidPx.asDouble();
        double bestAsk = askPx.asDouble();
        if (!(bestBid > 0) || !(bestAsk > 0)) return;

        int decimals = Math.max(decimalsOf(bidPx.asText()), decimalsOf(askPx.asText()));
        tops.put(symbol, new OrderBookTop(symbol, bestBid, bestAsk, decimals, System.currentTimeMillis()));
        updates.incrementAndGet();
        onUpdate.accept(symbol);
    }

    public long updateCount() {
        return updates.get();
    }

    public int symbolCount() {
        return tops.size();
    }

    /** "75636.1" -> 1, "174485877940" -> 0. Nobitex sends prices as strings, so this is exact. */
    static int decimalsOf(String price) {
        int dot = price.indexOf('.');
        if (dot < 0) return 0;
        int end = price.length();
        while (end > dot + 1 && price.charAt(end - 1) == '0') end--;
        return end - dot - 1;
    }

    public OrderBookTop get(String symbol) {
        return tops.get(symbol);
    }
}
