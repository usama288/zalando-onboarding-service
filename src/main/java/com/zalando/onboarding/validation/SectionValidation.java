package com.zalando.onboarding.validation;

import java.util.List;
import java.util.Map;

/**
 * The outcome of validating one section: what was wrong, and -- when nothing was -- the form
 * of the data to store.
 *
 * <p>Normalised values travel with the violations because the two are decided in the same
 * walk. Having P4 re-derive them would mean validating one string and persisting another.
 *
 * @param values the values to persist, with identifier fields in their normalised form.
 *               Meaningful only when {@link #isValid()}
 */
public record SectionValidation(List<Violation> violations, Map<String, Object> values) {

    public SectionValidation {
        violations = List.copyOf(violations);
        values = Map.copyOf(values);
    }

    public boolean isValid() {
        return violations.isEmpty();
    }
}
