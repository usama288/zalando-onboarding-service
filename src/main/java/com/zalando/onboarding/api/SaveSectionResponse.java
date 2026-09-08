package com.zalando.onboarding.api;

import com.zalando.onboarding.domain.OnboardingStep;
import com.zalando.onboarding.flow.ResumeStep;

/**
 * @param savedSection what was actually stored, in normalised form. Echoed back so the
 *                     client shows the persisted value rather than the one it typed: a NIP
 *                     entered as 856-734-62-15 is stored as 8567346215, and a form that
 *                     kept showing the typed form would disagree with the server
 */
public record SaveSectionResponse(OnboardingStep lastCompletedStep, ResumeStep resumeStep,
                                  SavedSection savedSection) {
}
