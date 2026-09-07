package com.zalando.onboarding.domain;

/**
 * The only two states an application has. Nothing else exists: there is no review,
 * approval or rejection workflow.
 */
public enum ApplicationStatus {
    DRAFT,
    SUBMITTED
}
