package com.zalando.onboarding.flow;

/**
 * Makes a field required only when another field in the same section holds a given value,
 * e.g. a VAT id required only once the applicant says they are VAT registered.
 *
 * <p><strong>Comparison contract.</strong> Fixed here, once, so that layer 1 (section save)
 * and layer 2 (submit completeness) cannot each invent their own and disagree about whether
 * an application is complete:
 *
 * <ol>
 *   <li>The observed value is coerced with {@link String#valueOf(Object)}, then trimmed, then
 *       compared to {@link #equals()} ignoring case. JSON {@code true}, YAML {@code "true"}
 *       and a form-posted {@code "True"} are therefore one rule, not three.</li>
 *   <li>An <em>absent</em> or null field never matches. The dependent field is then not
 *       required -- an unanswered controlling question cannot make anything mandatory,
 *       because we do not know the answer yet.</li>
 *   <li>Explicitly: {@code vatRegistered=false} does <em>not</em> make {@code vatId}
 *       required. Only the declared value matches; everything else, including the opposite
 *       answer, leaves the dependent field optional.</li>
 * </ol>
 *
 * <p>Rule 2 is why the controlling field must itself be unconditionally required. If
 * {@code vatRegistered} could be left blank, this returns false, {@code vatId} is never
 * asked for, and the application submits with a silent hole in it.
 *
 * @param field  the name of the controlling field, in this same section
 * @param equals the value it must hold, written as a string in YAML
 */
public record RequiredWhen(String field, String equals) {

    public RequiredWhen {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("requiredWhen.field is required");
        }
        if (equals == null) {
            throw new IllegalArgumentException("requiredWhen.equals is required for field " + field);
        }
    }

    /**
     * Whether the controlling field's observed value triggers the requirement.
     *
     * @param observedValue the value submitted for {@link #field()}, or null when the
     *                      applicant has not answered it
     */
    public boolean matches(Object observedValue) {
        if (observedValue == null) {
            return false;
        }
        return String.valueOf(observedValue).trim().equalsIgnoreCase(equals.trim());
    }
}
