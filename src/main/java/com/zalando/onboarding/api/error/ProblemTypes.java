package com.zalando.onboarding.api.error;

import java.net.URI;

/**
 * Stable {@code type} URIs for the problem bodies. These are identifiers, not links to
 * fetch: a client branches on the type or on a violation code, never on the title text.
 */
final class ProblemTypes {

    private static final String BASE = "https://onboarding.zalando.com/problems/";

    public static final URI VALIDATION_ERROR = URI.create(BASE + "validation-error");
    public static final URI NOT_FOUND = URI.create(BASE + "not-found");
    public static final URI CONFLICT = URI.create(BASE + "conflict");
    public static final URI INTERNAL_ERROR = URI.create(BASE + "internal-error");

    private ProblemTypes() {
    }
}
