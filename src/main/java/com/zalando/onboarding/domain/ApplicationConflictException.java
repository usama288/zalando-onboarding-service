package com.zalando.onboarding.domain;

import com.zalando.onboarding.validation.Violation;

/**
 * The request is well formed but the application is not in a state that permits it:
 * writing to a submitted application, reaching past an incomplete section, submitting an
 * email another submitted application already holds. Rendered as 409.
 *
 * <p>It carries a {@link Violation} so a state conflict renders through the same
 * {@code violations[]} path as a field error, rather than giving the client a second shape
 * to special-case.
 */
public class ApplicationConflictException extends RuntimeException {

    private final String title;
    private final transient Violation violation;

    public ApplicationConflictException(String title, Violation violation) {
        super(violation.message());
        this.title = title;
        this.violation = violation;
    }

    public String getTitle() {
        return title;
    }

    public Violation getViolation() {
        return violation;
    }
}
