package org.sarh.cycle.rest;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Base64;

/**
 * Ed25519 request signing for a Nobitex API key pair.
 *
 * <p>Per Nobitex's docs: {@code signature = base64(Ed25519(timestamp + method + urlPath + body))},
 * where {@code urlPath} includes the query string but not the scheme/host, and {@code body} is the
 * raw request body (empty for GET). Required headers: {@code Nobitex-Key}, {@code Nobitex-Signature},
 * {@code Nobitex-Timestamp}.
 *
 * <p>Both halves of the key pair are 44-character base64 strings and look identical -- passing the
 * public half where the private half belongs still "works" (32 bytes decode fine) but produces
 * signatures the server rejects with a bare 401.
 */
public final class NobitexAuth {

    public final String publicKey;
    private final PrivateKey privateKey;

    public NobitexAuth(String publicKeyB64, String privateKeyB64) {
        this.publicKey = publicKeyB64.trim();
        String privateTrimmed = privateKeyB64.trim();
        if (this.publicKey.equals(privateTrimmed)) {
            throw new IllegalArgumentException(
                    "public and private key are identical; an API key is a pair -- the private "
                            + "key is shown only once, when the key is created");
        }
        byte[] seed = decodeKey(privateTrimmed);
        try {
            NamedParameterSpec params = new NamedParameterSpec("Ed25519");
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            this.privateKey = kf.generatePrivate(new EdECPrivateKeySpec(params, seed));
        } catch (Exception e) {
            throw new IllegalArgumentException("private key is not a valid Ed25519 seed: " + e.getMessage(), e);
        }
    }

    /** Decodes a Nobitex key, accepting both standard and URL-safe base64, with or without padding. */
    static byte[] decodeKey(String value) {
        String cleaned = value.replaceAll("^[\"']|[\"']$", "");
        int pad = (4 - cleaned.length() % 4) % 4;
        String padded = cleaned + "=".repeat(pad);
        for (Base64.Decoder decoder : new Base64.Decoder[]{Base64.getDecoder(), Base64.getUrlDecoder()}) {
            try {
                byte[] raw = decoder.decode(padded);
                if (raw.length == 32) {
                    return raw;
                }
            } catch (IllegalArgumentException ignored) {
                // try the other alphabet
            }
        }
        throw new IllegalArgumentException(
                "key is not 32 bytes of base64 (got " + cleaned.length() + " chars)");
    }

    public record Signed(String signatureB64, String timestamp) {}

    public Signed sign(String method, String urlPathWithQuery, String body) {
        String timestamp = Long.toString(System.currentTimeMillis() / 1000L);
        String payload = timestamp + method.toUpperCase() + urlPathWithQuery + (body == null ? "" : body);
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(privateKey);
            sig.update(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = sig.sign();
            return new Signed(Base64.getEncoder().encodeToString(out), timestamp);
        } catch (Exception e) {
            throw new RuntimeException("failed to sign request", e);
        }
    }
}
