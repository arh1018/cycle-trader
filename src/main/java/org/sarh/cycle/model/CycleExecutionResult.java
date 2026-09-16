package org.sarh.cycle.model;

import java.util.List;

/** The two leg results of one cycle attempt, plus what they imply about inventory. */
public final class CycleExecutionResult {
    public final Opportunity opportunity;
    public final List<LegResult>
            legs; // always 2 entries; skipped legs recorded with amountIn = predecessor's out

    public CycleExecutionResult(Opportunity opportunity, List<LegResult> legs) {
        this.opportunity = opportunity;
        this.legs = List.copyOf(legs);
    }

    public boolean completed() {
        return legs.size() == 2 && legs.get(1).producedAnything();
    }

    /** Start-currency amount actually spent by leg 1. */
    public double startSpent() {
        return legs.isEmpty() ? 0 : legs.get(0).amountConsumed;
    }

    /** End-currency amount produced by leg 2 (0 if it never ran or matched nothing). */
    public double endReceived() {
        return legs.size() < 2 ? 0 : legs.get(1).amountOut;
    }

    /**
     * Coin left behind: leg 1's output that leg 2 did not consume. Leg 2 is recorded with {@code
     * amountIn} equal to leg 1's {@code amountOut}, so its leftover input is exactly this.
     */
    public double strandedMid() {
        if (legs.isEmpty() || !legs.get(0).producedAnything()) {
            return 0;
        }
        if (legs.size() < 2) {
            return legs.get(0).amountOut;
        }
        return legs.get(1).leftoverInput();
    }
}
