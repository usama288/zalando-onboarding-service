package com.zalando.onboarding.notification;

/**
 * The outbound seam for anything that would reach an applicant.
 *
 * <p>No email or SMS is ever sent by this service. What is built is the port and a recording
 * adapter; swapping in a provider is an adapter, not a redesign (A18, T-7). Implementations
 * are expected to enlist in the caller's transaction: a resume link that is recorded when the
 * draft it points at was rolled back is a link to nothing.
 */
public interface NotificationPort {

    void resumeLinkIssued(ResumeLinkIssued event);
}
