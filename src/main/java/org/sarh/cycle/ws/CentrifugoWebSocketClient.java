package org.sarh.cycle.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Minimal Centrifugo protocol v2 (JSON) client for Nobitex's public websocket.
 *
 * <p>Protocol notes that matter (see nbtrend's Python client, which this mirrors):
 *
 * <ul>
 *   <li>Commands are {@code {"<method>": {...}, "id": n}}; replies echo the id.
 *   <li>Pushes arrive as {@code {"push": {"channel": ..., "pub": {"data": "<json string>"}}}} --
 *       {@code data} is a JSON string, not an object, and needs a second parse.
 *   <li>A frame may carry several newline-delimited JSON objects.
 *   <li>The server sends {@code {}} as a ping; reply with {@code {}} within 25s or get dropped.
 * </ul>
 *
 * <p>Transport note: {@link WebSocket#sendText} refuses a new send while one is still pending, so
 * every outbound message is chained onto the previous one. Firing 300 subscribes in a plain loop
 * would drop all but the first with no visible error.
 */
public final class CentrifugoWebSocketClient {
    private static final Logger log = LoggerFactory.getLogger(CentrifugoWebSocketClient.class);
    private static final Duration RECONNECT_MIN = Duration.ofSeconds(1);
    private static final Duration RECONNECT_MAX = Duration.ofSeconds(60);

    private final String url;
    private final List<String> channels;
    private final BiConsumer<String, JsonNode> onPush; // (channel, decoded payload)
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicInteger nextId = new AtomicInteger(1);

    private volatile WebSocket ws;
    private volatile boolean stopping = false;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
    private final Object sendLock = new Object();

    public CentrifugoWebSocketClient(
            String url, List<String> channels, BiConsumer<String, JsonNode> onPush) {
        this.url = url;
        this.channels = channels;
        this.onPush = onPush;
    }

    public void start() {
        stopping = false;
        connectWithBackoff(RECONNECT_MIN);
    }

    public void stop() {
        stopping = true;
        WebSocket w = ws;
        if (w != null) {
            w.sendClose(WebSocket.NORMAL_CLOSURE, "stop");
        }
    }

    private void connectWithBackoff(Duration delay) {
        if (stopping) {
            return;
        }
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(url), new Listener())
                .whenComplete(
                        (socket, err) -> {
                            if (err != null) {
                                log.warn(
                                        "websocket connect failed ({}), retrying in {}s",
                                        err.toString(),
                                        delay.getSeconds());
                                scheduleReconnect(delay);
                                return;
                            }
                            this.ws = socket;
                            synchronized (sendLock) {
                                sendChain = CompletableFuture.completedFuture(null);
                            }
                            send("{\"connect\":{},\"id\":" + nextId.getAndIncrement() + "}");
                            for (String ch : channels) {
                                send(
                                        "{\"subscribe\":{\"channel\":\""
                                                + ch
                                                + "\"},\"id\":"
                                                + nextId.getAndIncrement()
                                                + "}");
                            }
                            log.info(
                                    "websocket connected, subscribing to {} channel(s)",
                                    channels.size());
                        });
    }

    private void scheduleReconnect(Duration delay) {
        if (stopping) {
            return;
        }
        CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS)
                .execute(() -> connectWithBackoff(nextDelay(delay)));
    }

    private static Duration nextDelay(Duration current) {
        Duration doubled = current.multipliedBy(2);
        return doubled.compareTo(RECONNECT_MAX) > 0 ? RECONNECT_MAX : doubled;
    }

    /** Serialises outbound messages; see the class comment. */
    private void send(String text) {
        synchronized (sendLock) {
            sendChain =
                    sendChain
                            .handle((v, e) -> null)
                            .thenCompose(
                                    v -> {
                                        WebSocket w = ws;
                                        if (w == null || w.isOutputClosed()) {
                                            return CompletableFuture.completedFuture(null);
                                        }
                                        return w.sendText(text, true);
                                    })
                            .whenComplete(
                                    (v, e) -> {
                                        if (e != null) {
                                            log.warn("websocket send failed: {}", e.toString());
                                        }
                                    });
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            webSocket.request(1);
            if (last) {
                String frame = buffer.toString();
                buffer.setLength(0);
                handleFrame(frame);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("websocket closed: {} {}", statusCode, reason);
            if (!stopping) {
                scheduleReconnect(RECONNECT_MIN);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("websocket error: {}", error.toString());
            if (!stopping) {
                scheduleReconnect(RECONNECT_MIN);
            }
        }
    }

    private void handleFrame(String frame) {
        for (String line : frame.split("\n")) {
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            JsonNode message;
            try {
                message = json.readTree(line);
            } catch (Exception e) {
                log.debug("undecodable frame fragment: {}", line);
                continue;
            }
            handleMessage(message);
        }
    }

    private void handleMessage(JsonNode message) {
        if (message.isEmpty()) {
            send("{}"); // server ping -> echo, or be disconnected after 25s
            return;
        }
        if (message.has("push")) {
            JsonNode push = message.get("push");
            String channel = push.path("channel").asText("");
            JsonNode dataNode = push.path("pub").path("data");
            if (dataNode.isMissingNode() || dataNode.isNull()) {
                return;
            }
            JsonNode payload;
            try {
                payload = dataNode.isTextual() ? json.readTree(dataNode.asText()) : dataNode;
            } catch (Exception e) {
                log.warn("undecodable payload on {}", channel);
                return;
            }
            try {
                onPush.accept(channel, payload);
            } catch (Exception e) {
                log.error("handler for {} raised", channel, e);
            }
            return;
        }
        if (message.has("error")) {
            log.error("centrifugo error: {}", message.get("error"));
            return;
        }
        if (message.has("connect")) {
            log.info("connected: {}", message.get("connect"));
        }
        // "subscribe" acks are ignored.
    }
}
