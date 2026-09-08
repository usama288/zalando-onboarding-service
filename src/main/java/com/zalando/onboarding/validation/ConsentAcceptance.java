package com.zalando.onboarding.validation;

import java.util.Map;

/**
 * What it means for a consent to have been accepted. One definition, shared by both
 * validation layers.
 *
 * <p>It lives here rather than in either validator because the two layers asking the same
 * question and answering it differently is the failure mode that matters: layer 1 would
 * store a value layer 2 then rejects, or -- as happened before this was extracted -- layer 1
 * would accept a refusal that layer 2 never re-examined.
 *
 * <p>Acceptance is an affirmative act. A consent that is absent and a consent that is present
 * and false are the same answer: no. Only two shapes count as yes -- the
 * {@code {version, acceptedAt}} acceptance record of A11, and a bare {@code true} for
 * clients that do not send the record.
 */
public final class ConsentAcceptance {

    public static boolean isAccepted(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Map<?, ?> record) {
            Object acceptedAt = record.get("acceptedAt");
            return acceptedAt != null && !String.valueOf(acceptedAt).trim().isEmpty();
        }
        return String.valueOf(value).trim().equalsIgnoreCase("true");
    }

    private ConsentAcceptance() {
    }
}
