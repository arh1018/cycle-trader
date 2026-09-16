package org.sarh.cycle.model;

public enum Side {
    BUY,
    SELL;

    public String wireValue() {
        return name().toLowerCase();
    }
}
