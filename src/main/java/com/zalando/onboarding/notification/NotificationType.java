package com.zalando.onboarding.notification;

/**
 * What a recorded notification would have been, had anything been sent.
 *
 * <p>One member today. It is an enum rather than a bare string because the outbox is the
 * seam a real adapter plugs into, and an adapter has to switch on something.
 */
public enum NotificationType {

    /** The resume link issued when a draft is created. Also the future verification link (A20). */
    RESUME_LINK
}
