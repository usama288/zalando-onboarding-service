package com.zalando.onboarding.api;

import com.zalando.onboarding.domain.ApplicationStatus;
import java.time.Instant;

/**
 * The same body whether this call performed the submission or found it already done —
 * submission is idempotent, so a double-click must not be able to tell the difference.
 */
public record SubmitResponse(String reference, ApplicationStatus status, Instant submittedAt) {
}
