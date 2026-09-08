package com.zalando.onboarding.api;

import com.zalando.onboarding.notification.NotificationOutboxRepository;
import com.zalando.onboarding.notification.OutboxNotification;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads the notification outbox so resume can be demonstrated end to end: create a draft,
 * look the resume link up here, open it.
 *
 * <p>Dev profile only, and that annotation is load-bearing rather than tidiness. The payload
 * contains a resume link, the link contains a bearer credential, and this endpoint has no
 * authentication in front of it — exposing it in production would publish every live draft.
 */
@RestController
@RequestMapping("/api/dev/outbox")
@Profile("dev")
public class DevOutboxController {

    private final NotificationOutboxRepository outbox;

    public DevOutboxController(NotificationOutboxRepository outbox) {
        this.outbox = outbox;
    }

    @GetMapping
    public List<OutboxNotification> all() {
        return outbox.findAllByOrderByCreatedAtDesc();
    }
}
