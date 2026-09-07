package com.zalando.onboarding.validation;

import java.util.Optional;

/**
 * A named field rule, referenced from flow YAML by {@link #name()} and implemented here.
 *
 * <p>The name is the contract between configuration and code: {@code validators: [iban]} in
 * a flow definition resolves to the bean whose {@code name()} returns {@code "iban"}. That
 * resolution is checked at startup, so a name matching nothing stops the application rather
 * than silently skipping the rule.
 *
 * <p>Implementations must treat a null or blank value as <em>not their problem</em> and
 * return empty: whether absence is allowed is {@code required} and {@code requiredWhen}'s
 * question, answered by {@link SectionValidator} before any validator runs. A validator that
 * also reported absence would produce two violations for one mistake.
 */
public interface FieldValidator {

    /** The name used to reference this rule from YAML. Must be unique across all beans. */
    String name();

    /**
     * @param field the field name, so the returned violation can identify itself
     * @param value the submitted value, as it arrived from JSON
     * @return a violation, or empty if the value passes or is absent
     */
    Optional<Violation> validate(String field, Object value);

    /**
     * The form of the value to store. Presentational separators are the applicant's business,
     * not the database's: a Pole writes a NIP as 856-734-62-15 and must not be told it is
     * wrong, but only one form should end up persisted so stored values compare equal.
     *
     * <p>Defaults to storing exactly what arrived.
     */
    default Object normalise(Object value) {
        return value;
    }

    /** Shared normalisation for the identifier rules: drop spaces and hyphens, uppercase. */
    static String stripSeparators(String value) {
        return value == null ? null : value.replaceAll("[\\s-]", "").toUpperCase();
    }
}
