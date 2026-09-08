package com.zalando.onboarding.api;

import com.zalando.onboarding.domain.ApplicationStatus;
import com.zalando.onboarding.domain.Country;
import com.zalando.onboarding.flow.FlowDefinition;
import java.time.Instant;
import java.util.Map;

/**
 * Retrieval by reference. Read-only: there is no editing or withdrawal after submission, so
 * this view has no counterpart that writes.
 *
 * <p>The flow travels with it because {@code formData} is field names and values; without
 * the definition a client has nothing to label them with.
 */
public record SubmittedApplicationView(String reference, ApplicationStatus status,
                                       Instant submittedAt, Country country, String email,
                                       Map<String, Object> formData, FlowDefinition flow) {
}
