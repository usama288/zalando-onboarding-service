package com.zalando.onboarding.api;

import com.zalando.onboarding.domain.ApplicationStatus;
import com.zalando.onboarding.domain.Country;
import com.zalando.onboarding.domain.OnboardingStep;
import com.zalando.onboarding.flow.FlowDefinition;
import com.zalando.onboarding.flow.ResumeStep;
import java.time.Instant;
import java.util.Map;

/**
 * What the holder of a draft token sees.
 *
 * @param lastCompletedStep the fact the row stores: the furthest section actually completed,
 *                          null on a fresh draft
 * @param resumeStep        derived from it on the way out and never stored. Where the client
 *                          should send the applicant; viewing position is the client's own
 *                          state and belongs in its URL
 * @param reference         null until submitted. Withholding it from the token holder
 *                          protected nothing -- they may already read every field of the
 *                          application -- and stranded the one person entitled to it
 * @param submittedAt       null until submitted
 */
public record ApplicationView(ApplicationStatus status, Country country, String email,
                              OnboardingStep lastCompletedStep, ResumeStep resumeStep,
                              String reference, Instant submittedAt,
                              Map<String, Object> formData, FlowDefinition flow) {
}
