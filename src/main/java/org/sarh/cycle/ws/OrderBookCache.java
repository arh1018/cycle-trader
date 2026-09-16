package org.sarh.cycle.ws;

import com.fasterxml.jackson.databind.JsonNode;

import org.sarh.cycle.model.OrderBook;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Thread-safe depth cache, fed by the websocket (or a REST snapshot), read by the engine. */
public final class OrderBookCache {
    private final ConcurrentHashMap<String, OrderBook> books = new ConcurrentHashMap<>();
    private final AtomicLong updates = new AtomicLong();
    private final int maxLevels;
    private final Consumer<String> onUpdate; // symbol that just changed

    public OrderBookCache(int maxLevels, Consumer<String> onUpdate) {
        this.maxLevels = maxLevels;
        this.onUpdate = onUpdate;
    }

    public static String symbolFromChannel(String channel) {
        int dash = channel.lastIndexOf('-');
        return dash < 0 ? channel : channel.substring(dash + 1);
    }

    /**
     * Handler for {@code CentrifugoWebSocketClient}'s push callback; also accepts a REST book node.
     */
    public void onOrderbookPush(String channel, JsonNode payload) {
        String symbol = symbolFromChannel(channel);
        JsonNode bids = payload.path("bids");
        JsonNode asks = payload.path("asks");
        if (!bids.isArray() || bids.isEmpty() || !asks.isArray() || asks.isEmpty()) {
            return;
        }

        int nb = Math.min(maxLevels, bids.size()), na = Math.min(maxLevels, asks.size());
        double[] bidPx = new double[nb],
                bidSz = new double[nb],
                askPx = new double[na],
                askSz = new double[na];
        int priceDecimals = 0, sizeDecimals = 0;
        for (int i = 0; i < nb; i++) {
            JsonNode lvl = bids.get(i);
            bidPx[i] = lvl.get(0).asDouble();
            bidSz[i] = lvl.get(1).asDouble();
            if (i == 0) {
                priceDecimals = decimalsOf(lvl.get(0).asText());
            }
            sizeDecimals = Math.max(sizeDecimals, decimalsOf(lvl.get(1).asText()));
        }
        for (int i = 0; i < na; i++) {
            JsonNode lvl = asks.get(i);
            askPx[i] = lvl.get(0).asDouble();
            askSz[i] = lvl.get(1).asDouble();
            if (i == 0) {
                priceDecimals = Math.max(priceDecimals, decimalsOf(lvl.get(0).asText()));
            }
            sizeDecimals = Math.max(sizeDecimals, decimalsOf(lvl.get(1).asText()));
        }
        if (!(bidPx[0] > 0) || !(askPx[0] > 0)) {
            return;
        }

        books.put(
                symbol,
                new OrderBook(
                        symbol,
                        bidPx,
                        bidSz,
                        askPx,
                        askSz,
                        priceDecimals,
                        sizeDecimals,
                        System.currentTimeMillis()));
        updates.incrementAndGet();
        onUpdate.accept(symbol);
    }

    /** "75636.1" -> 1, "174485877940" -> 0. Nobitex sends numbers as strings, so this is exact. */
    static int decimalsOf(String s) {
        int dot = s.indexOf('.');
        if (dot < 0) {
            return 0;
        }
        int end = s.length();
        while (end > dot + 1 && s.charAt(end - 1) == '0') {
            end--;
        }
        return end - dot - 1;
    }

    public OrderBook get(String symbol) {
        return books.get(symbol);
    }

    public long updateCount() {
        return updates.get();
    }

    public int symbolCount() {
        return books.size();
    }
}
