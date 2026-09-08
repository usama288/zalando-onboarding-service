package com.zalando.onboarding.notification;

import java.util.UUID;

/**
 * The event: a resume link now exists for this application and its holder should receive it.
 *
 * <p>Carries the link rather than the token so the port never has to know how a link is
 * assembled, and so nothing downstream is tempted to build a second, different one.
 *
 * @param resumeUrl a bearer credential in URL form. Never log this (A13)
 */
public record ResumeLinkIssued(UUID applicationId, String recipient, String resumeUrl) {
}
