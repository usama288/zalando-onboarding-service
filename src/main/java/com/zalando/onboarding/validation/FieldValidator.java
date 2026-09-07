package com.zalando.onboarding.validation;

/**
 * A named field rule, referenced from flow YAML by {@link #name()} and implemented here.
 *
 * <p>The name is the contract between configuration and code: {@code validators: [iban]} in
 * a flow definition resolves to the bean whose {@code name()} returns {@code "iban"}. That
 * resolution is checked at startup, so a name that matches nothing stops the application
 * rather than silently skipping the rule.
 *
 * <p>P2 declares only the name. The validation method arrives in P3, alongside the
 * violation type it reports into.
 */
public interface FieldValidator {

    /** The name used to reference this rule from YAML. Must be unique across all beans. */
    String name();
}
