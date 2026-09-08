package com.zalando.onboarding.decision;

import java.time.Instant;
import java.util.List;

/**
 * What the mocked checks concluded. Persisted to {@code applications.decision} and never
 * returned by any endpoint.
 *
 * @param identityConfidence 0–100. How well the application agrees with itself: the payout
 *                           account naming the applicant, the IBAN belonging to the country
 *                           of registration. Not a probability, and not calibrated against
 *                           anything — it is a mock
 * @param debtFlags          machine-readable codes, never the value that triggered them.
 *                           A decision blob that quoted the tax number would put the same
 *                           identifier in a second place for no gain
 * @param finalDecision      the outcome the flags and confidence imply
 * @param decidedAt          when, from the injected clock
 */
public record Decision(int identityConfidence, List<String> debtFlags,
                       FinalDecision finalDecision, Instant decidedAt) {

    /** A match against the demo debt register. There is no real bureau integration. */
    public static final String DEBT_REGISTER_MATCH = "DEBT_REGISTER_MATCH";

    public Decision {
        debtFlags = List.copyOf(debtFlags == null ? List.of() : debtFlags);
    }
}
