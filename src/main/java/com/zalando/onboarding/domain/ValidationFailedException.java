package com.zalando.onboarding.domain;

import com.zalando.onboarding.validation.Violation;
import java.util.List;

/**
 * Layer 1 or layer 2 refused the request. Rendered as 400 with the whole
 * {@code violations[]} array, which is the contract both layers share.
 *
 * <p>Thrown, not returned, precisely because it must abort the transaction before anything
 * is persisted: "nothing is persisted if it fails" is not a thing a caller can forget.
 */
public class ValidationFailedException extends RuntimeException {

    private final String title;
    private final transient List<Violation> violations;

    public ValidationFailedException(String title, List<Violation> violations) {
        super(title + ": " + violations.size() + " violation(s)");
        this.title = title;
        this.violations = List.copyOf(violations);
    }

    public static ValidationFailedException of(String title, Violation violation) {
        return new ValidationFailedException(title, List.of(violation));
    }

    public String getTitle() {
        return title;
    }

    public List<Violation> getViolations() {
        return violations;
    }
}
