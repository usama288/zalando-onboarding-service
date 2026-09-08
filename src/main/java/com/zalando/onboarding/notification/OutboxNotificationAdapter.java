package com.zalando.onboarding.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The adapter that ships: it writes the notification down and stops there (A18).
 *
 * <p>It joins whatever transaction the caller already has — no {@code @Transactional} of its
 * own, no {@code REQUIRES_NEW} — so a resume link cannot outlive a rolled-back draft.
 *
 * <p>The outbox, not a log line, is where the link goes. The link contains the draft token,
 * the token is a bearer credential, and logging it would hand a resume link to anyone with
 * log access (A13).
 */
@Component
public class OutboxNotificationAdapter implements NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(OutboxNotificationAdapter.class);

    private final NotificationOutboxRepository outbox;
    private final Clock clock;

    public OutboxNotificationAdapter(NotificationOutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public void resumeLinkIssued(ResumeLinkIssued event) {
        Instant now = Instant.now(clock);
        outbox.save(OutboxNotification.record(
                UUID.randomUUID(),
                event.applicationId(),
                NotificationType.RESUME_LINK,
                event.recipient(),
                Map.of("resumeUrl", event.resumeUrl()),
                now));

        log.info("Recorded {} notification for application {}",
                NotificationType.RESUME_LINK, event.applicationId());
    }
}
