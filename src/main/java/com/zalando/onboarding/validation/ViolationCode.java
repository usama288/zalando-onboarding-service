package com.zalando.onboarding.validation;

/**
 * Machine-readable violation codes. The client renders from {@code code}; the human message
 * beside it is for logs and for a fallback rendering, never for branching on.
 */
public final class ViolationCode {

    /** A required field was absent or blank, or a requiredWhen condition fired and it was not supplied. */
    public static final String REQUIRED = "REQUIRED";

    /** Present, but the wrong shape: failed a declared pattern or a validator's format rule. */
    public static final String PATTERN_INVALID = "PATTERN_INVALID";

    /** Correctly shaped but arithmetically wrong: a check digit did not verify. */
    public static final String CHECKSUM_INVALID = "CHECKSUM_INVALID";

    /** Longer than the declared maxLength. */
    public static final String TOO_LONG = "TOO_LONG";

    /** A date of birth less than 18 years before today. */
    public static final String NOT_ADULT = "NOT_ADULT";

    /** Layer 2: this country's flow requires a section the application has not completed. */
    public static final String SECTION_MISSING = "SECTION_MISSING";

    /** The payload carried a field this section's definition does not declare. */
    public static final String FIELD_UNKNOWN = "FIELD_UNKNOWN";

    /** The request named a section this country's flow does not have. Used from P4's API. */
    public static final String SECTION_UNKNOWN = "SECTION_UNKNOWN";

    private ViolationCode() {
    }
}
