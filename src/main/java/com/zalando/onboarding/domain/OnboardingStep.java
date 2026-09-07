package com.zalando.onboarding.domain;

/**
 * The ordered sections an applicant completes. Declaration order is the step order and
 * matches the CHECK constraint on {@code applications.last_completed_step}.
 *
 * <p>REVIEW is deliberately not a member. It is a screen that persists nothing, so it can
 * never be a <em>completed</em> step; including it would turn progress into viewing
 * position. Which sections a given country actually requires is declared in that country's
 * flow definition, not here.
 */
public enum OnboardingStep {
    PERSONAL_DETAILS,
    ADDRESS,
    TAX_INFORMATION,
    BUSINESS_REGISTRY,
    PAYMENT_DETAILS,
    CONSENT
}
