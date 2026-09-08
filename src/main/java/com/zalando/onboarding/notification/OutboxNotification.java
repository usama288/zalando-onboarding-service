package com.zalando.onboarding.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One notification that would have been sent. Written, never dispatched.
 *
 * <p>{@code application_id} is a plain column rather than a {@code @ManyToOne}: the outbox
 * records what happened, it does not navigate the domain, and an association here would let
 * a notification lazily drag an application into memory behind a caller's back.
 */
@Entity
@Table(name = "notification_outbox")
public class OutboxNotification {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40, updatable = false)
    private NotificationType type;

    @Column(name = "recipient", nullable = false, length = 320, updatable = false)
    private String recipient;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OutboxNotification() {
        // for JPA
    }

    public static OutboxNotification record(UUID id, UUID applicationId, NotificationType type,
                                            String recipient, Map<String, Object> payload,
                                            Instant now) {
        OutboxNotification notification = new OutboxNotification();
        notification.id = id;
        notification.applicationId = applicationId;
        notification.type = type;
        notification.recipient = recipient;
        notification.payload = Map.copyOf(payload);
        notification.createdAt = now;
        return notification;
    }

    public UUID getId() {
        return id;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public NotificationType getType() {
        return type;
    }

    public String getRecipient() {
        return recipient;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Identifiers only. The recipient and the link inside the payload are both secrets (A13). */
    @Override
    public String toString() {
        return "OutboxNotification[id=" + id + ", applicationId=" + applicationId
                + ", type=" + type + "]";
    }
}
