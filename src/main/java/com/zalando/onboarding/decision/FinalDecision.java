package com.zalando.onboarding.decision;

/**
 * The outcome of the mocked post-submission checks.
 *
 * <p>Deliberately not an application status. The application is SUBMITTED and stays SUBMITTED
 * (A1, FR6) — there are exactly two statuses and this is not one of them. This is a note
 * attached to a submitted application for a reviewer who does not exist yet.
 */
public enum FinalDecision {

    /** Nothing in the mocked signals argues against onboarding. */
    APPROVE,

    /** Something is inconsistent enough to want a human, once there is one. */
    REFER,

    /** A hard flag. In a real system this is where an adverse-action process would begin. */
    DECLINE
}
