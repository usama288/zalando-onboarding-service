package com.zalando.onboarding.api;

/**
 * Step 0: the two things captured before any section exists.
 *
 * <p>Both arrive as strings and are normalised and checked by the service, not here, so that
 * a bad country produces the same {@code violations[]} body as a bad postcode instead of a
 * Jackson deserialisation failure.
 */
public record CreateApplicationRequest(String email, String country) {
}
