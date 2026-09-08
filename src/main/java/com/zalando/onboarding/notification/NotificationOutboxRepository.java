package com.zalando.onboarding.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationOutboxRepository extends JpaRepository<OutboxNotification, UUID> {

    /** Newest first, for the dev-profile endpoint that makes resume demonstrable. */
    List<OutboxNotification> findAllByOrderByCreatedAtDesc();

    List<OutboxNotification> findByApplicationIdOrderByCreatedAtDesc(UUID applicationId);
}
