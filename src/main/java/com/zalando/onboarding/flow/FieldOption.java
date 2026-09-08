package com.zalando.onboarding.flow;

/**
 * One choice in a field's closed set of values.
 *
 * <p>Carries its own label because the client cannot derive one: {@code HRA} is not a word,
 * and inventing a lookup in JavaScript would put a piece of German company law into the
 * frontend, which is exactly where country rules must never live.
 *
 * @param value what is submitted and stored
 * @param label what the applicant reads
 */
public record FieldOption(String value, String label) {

    public FieldOption {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("option value is required");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("option " + value + " has no label");
        }
    }
}
