package org.sarh.cycle.rest;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sarh.cycle.model.Side;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thin REST client for the public market-list and (when credentials are present) trading endpoints. */
public final class NobitexRestClient {

    private final String baseUrl;
    private final NobitexAuth auth; // null in paper mode / no credentials
    private final HttpClient http;
    // Prices and amounts go out as plain decimals. A double would serialise a rial price like
    // 174485877940 as 1.7448587794E11, which the exchange rejects.
    private final ObjectMapper json = new ObjectMapper()
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    public NobitexRestClient(String baseUrl, NobitexAuth auth) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.auth = auth;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean isAuthenticated() {
        return auth != null;
    }

    /** GET /v3/orderbook/all -- every market with a live book, keyed by symbol (plus a "status" field). */
    public JsonNode orderbookAll() {
        return get("/v3/orderbook/all", "");
    }

    /** GET /v2/options -- coin metadata including display precision. */
    public JsonNode options() {
        return get("/v2/options", "");
    }

    /** GET /market/stats?srcCurrency=a,b,c&dstCurrency=rls -- 24h stats. */
    public JsonNode marketStats(String currenciesCsv, String dst) {
        String query = "?srcCurrency=" + currenciesCsv + "&dstCurrency=" + dst;
        return get("/market/stats", query).path("stats");
    }

    public record OrderReceipt(Long id, String clientOrderId, String status) {}

    /**
     * POST /market/orders/add -- places a limit order.
     *
     * <p>{@code amount} is always in the market's src currency, for both sides. {@code price} is in
     * the market's dst unit (rial for *IRT, USDT for *USDT) and must already be rounded to a
     * precision the market accepts.
     */
    public OrderReceipt addOrder(String src, String dst, Side side, BigDecimal amount, BigDecimal price,
                                  String clientOrderId) {
        requireAuth();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", side.wireValue());
        body.put("srcCurrency", src);
        body.put("dstCurrency", dst);
        body.put("amount", amount.stripTrailingZeros());
        body.put("execution", "limit");
        body.put("price", price.stripTrailingZeros());
        body.put("clientOrderId", clientOrderId);
        JsonNode data = post("/market/orders/add", body);
        JsonNode order = data.path("order");
        Long id = order.hasNonNull("id") ? order.get("id").asLong() : null;
        return new OrderReceipt(id, clientOrderId, order.path("status").asText(""));
    }

    /** POST /market/orders/status. The numeric field is {@code id} here (it is {@code order} on cancel). */
    public JsonNode orderStatus(Long orderId, String clientOrderId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (orderId != null) body.put("id", orderId);
        else if (clientOrderId != null) body.put("clientOrderId", clientOrderId);
        return post("/market/orders/status", body).path("order");
    }

    /** POST /market/orders/update-status. clientOrderId is the handle that reliably cancels. */
    public boolean cancelOrder(Long orderId, String clientOrderId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "canceled");
        if (clientOrderId != null) body.put("clientOrderId", clientOrderId);
        else if (orderId != null) body.put("order", orderId);
        return "ok".equals(post("/market/orders/update-status", body).path("status").asText());
    }

    /** Free (not blocked in open orders) balance of one currency, from /users/wallets/list. */
    public double freeBalance(String currency) {
        requireAuth();
        JsonNode wallets = get("/users/wallets/list", "").path("wallets");
        for (JsonNode w : wallets) {
            if (currency.equalsIgnoreCase(w.path("currency").asText(""))) {
                return w.path("activeBalance").asDouble(0);
            }
        }
        return 0;
    }

    // -- plumbing ------------------------------------------------------------
    private void requireAuth() {
        if (auth == null) {
            throw new IllegalStateException("this call needs NOBITEX_API_KEY / NOBITEX_API_SECRET");
        }
    }

    private JsonNode get(String path, String query) {
        String urlPath = path + query;
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + urlPath))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "TraderBot/cycle-trader")
                .GET();
        if (auth != null) sign(rb, "GET", urlPath, "");
        return execute(rb.build());
    }

    private JsonNode post(String path, Map<String, Object> body) {
        requireAuth();
        String bodyJson;
        try {
            bodyJson = json.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "TraderBot/cycle-trader")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson));
        sign(rb, "POST", path, bodyJson);
        return execute(rb.build());
    }

    private void sign(HttpRequest.Builder rb, String method, String urlPath, String body) {
        NobitexAuth.Signed signed = auth.sign(method, urlPath, body);
        rb.header("Nobitex-Key", auth.publicKey)
          .header("Nobitex-Signature", signed.signatureB64())
          .header("Nobitex-Timestamp", signed.timestamp());
    }

    private JsonNode execute(HttpRequest request) {
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new NobitexApiException(
                        "HTTP " + resp.statusCode() + ": " + resp.body(), "http-" + resp.statusCode());
            }
            JsonNode root = json.readTree(resp.body());
            if ("failed".equals(root.path("status").asText())) {
                throw new NobitexApiException(
                        root.path("message").asText("request failed"), root.path("code").asText(""));
            }
            return root;
        } catch (NobitexApiException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("request to " + request.uri() + " failed", e);
        }
    }
}
