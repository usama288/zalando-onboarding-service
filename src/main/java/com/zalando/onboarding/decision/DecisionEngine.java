package com.zalando.onboarding.decision;

import com.zalando.onboarding.domain.Application;

/**
 * The seam. Mocked checks run behind it today; a real provider, or a queue, replaces the
 * implementation without touching a caller.
 *
 * <p>It takes the whole application rather than a bag of extracted fields because which
 * fields matter is a property of the country's flow, and the flow is what the implementation
 * consults. Handing it pre-extracted values would put that knowledge in the caller.
 */
public interface DecisionEngine {

    /**
     * @param application a SUBMITTED application. Implementations must be side-effect free:
     *                    the caller decides what to do with the result
     */
    Decision decide(Application application);
}
