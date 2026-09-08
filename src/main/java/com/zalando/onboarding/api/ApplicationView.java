package com.zalando.onboarding.api;

import com.zalando.onboarding.domain.ApplicationStatus;
import com.zalando.onboarding.domain.Country;
import com.zalando.onboarding.domain.OnboardingStep;
import com.zalando.onboarding.flow.FlowDefinition;
import com.zalando.onboarding.flow.ResumeStep;
import java.util.Map;

/**
 * What the holder of a draft token sees.
 *
 * @param lastCompletedStep the fact the row stores: the furthest section actually completed,
 *                          null on a fresh draft
 * @param resumeStep        derived from it on the way out and never stored. Where the client
 *                          should send the applicant; viewing position is the client's own
 *                          state and belongs in its URL
 */
public record ApplicationView(ApplicationStatus status, Country country, String email,
                              OnboardingStep lastCompletedStep, ResumeStep resumeStep,
                              Map<String, Object> formData, FlowDefinition flow) {
}
