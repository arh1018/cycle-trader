package org.sarh.cycle.rest;

/** A request the exchange answered with {@code status: failed} or an HTTP error. */
public class NobitexApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public final String code;

    public NobitexApiException(String message, String code) {
        super(message);
        this.code = code;
    }
}
