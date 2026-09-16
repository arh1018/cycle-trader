package org.sarh.cycle.engine;

import org.sarh.cycle.model.Opportunity;
import org.sarh.cycle.model.TriangleExecutionResult;

public interface Executor {
    TriangleExecutionResult execute(Opportunity opportunity);
}
