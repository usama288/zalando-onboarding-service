package com.zalando.onboarding.domain;

/**
 * No application matches the credential presented. Rendered as 404.
 *
 * <p>The message deliberately never echoes the token or reference that missed: it reaches a
 * log line, and both are bearer credentials (A13, T-3). "Which one?" is answered by the
 * requestId, not by reproducing the secret.
 */
public class ApplicationNotFoundException extends RuntimeException {

    public ApplicationNotFoundException(String message) {
        super(message);
    }
}
