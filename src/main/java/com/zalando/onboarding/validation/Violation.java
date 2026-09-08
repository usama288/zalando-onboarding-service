package com.zalando.onboarding.validation;

/**
 * One reason a payload was refused. Both layers emit these, so the client has a single
 * rendering path for section saves and for submission.
 *
 * <p>A violation names either a field or a section: field-level rules fill {@code field}
 * and, once collected by {@link SectionValidator}, {@code section} too; section-level rules
 * fill only {@code section}.
 *
 * @param code    machine-readable, from {@link ViolationCode}. This is the contract
 * @param message human-readable, for logs and fallback rendering. Never branch on it
 */
public record Violation(String field, String section, String code, String message) {

    /** A field-level violation, before it knows which section it was found in. */
    public static Violation field(String field, String code, String message) {
        return new Violation(field, null, code, message);
    }

    /**
     * A violation of the application as a whole, naming neither a field nor a section --
     * "this application has been submitted" is not about any one part of the form.
     */
    public static Violation application(String code, String message) {
        return new Violation(null, null, code, message);
    }

    /** A section-level violation, naming no single field. */
    public static Violation section(String section, String code, String message) {
        return new Violation(null, section, code, message);
    }

    /** Stamps the section a field-level violation was found in. */
    public Violation inSection(String section) {
        return new Violation(field, section, code, message);
    }
}
