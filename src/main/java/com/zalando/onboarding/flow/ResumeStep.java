package com.zalando.onboarding.flow;

import com.zalando.onboarding.domain.OnboardingStep;

/**
 * Where to send the applicant next. Deliberately a different type from
 * {@link OnboardingStep}: that one is persisted and records the six sections actually
 * completed, this one is returned and adds REVIEW, the screen that persists nothing.
 *
 * <p>Modelling this as an empty {@code Optional<OnboardingStep>} would conflate "go to
 * review" with "nothing to do", which are not the same answer.
 */
public enum ResumeStep {
    PERSONAL_DETAILS,
    ADDRESS,
    TAX_INFORMATION,
    BUSINESS_REGISTRY,
    PAYMENT_DETAILS,
    CONSENT,
    REVIEW;

    static ResumeStep of(OnboardingStep step) {
        return valueOf(step.name());
    }
}
