package com.zalando.onboarding.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * A single onboarding application.
 *
 * <p>The state machine is DRAFT to SUBMITTED, once, and the type is built so the invalid
 * shapes cannot be constructed: there is no public constructor and no setters, a draft
 * cannot carry a reference or a submission time, and a submitted application cannot be
 * edited. The database enforces the same rule independently in
 * {@code application_submission_state}.
 */
@Entity
@Table(name = "applications")
public class Application {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "draft_token_hash", nullable = false, updatable = false, columnDefinition = "text")
    private String draftTokenHash;

    @Column(name = "reference", length = 24)
    private String reference;

    @Column(name = "applicant_email", nullable = false, length = 320)
    private String applicantEmail;

    @Enumerated(EnumType.STRING)
    // CHAR(2), not VARCHAR: the column is exactly an ISO 3166-1 alpha-2 code.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country", nullable = false, updatable = false, columnDefinition = "char(2)")
    private Country country;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ApplicationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_completed_step", length = 30)
    private OnboardingStep lastCompletedStep;

    @Column(name = "schema_version", nullable = false)
    private short schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form_data", nullable = false)
    private Map<String, Object> formData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "decision")
    private Map<String, Object> decision;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Owned by Hibernate, and by Hibernate alone. There is deliberately no database trigger
     * and no assignment anywhere in this class: one writer, so the column cannot be written
     * twice with two different clocks. DEFAULT NOW() in the DDL fires on INSERT only and is
     * a floor for out-of-band writes, not a second owner.
     *
     * <p>Null on a transient instance; Hibernate populates it on insert and on every update.
     *
     * <p>The cost, accepted: a raw SQL UPDATE that bypasses JPA leaves this stale. Nothing
     * outside the application writes this table.
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Current shape of the {@code form_data} blob. */
    private static final short CURRENT_SCHEMA_VERSION = 1;

    /** Key stamped into every stored section, so drop-off per step is answerable (A2). */
    private static final String COMPLETED_AT = "completedAt";

    protected Application() {
        // for JPA
    }

    /**
     * The only way to bring an application into existence: as an empty draft.
     *
     * @param draftTokenHash hash of the resume token, never the token itself
     */
    public static Application newDraft(UUID id, String draftTokenHash, String applicantEmail,
                                       Country country, Instant now) {
        Application application = new Application();
        application.id = Objects.requireNonNull(id, "id");
        application.draftTokenHash = Objects.requireNonNull(draftTokenHash, "draftTokenHash");
        application.applicantEmail = Objects.requireNonNull(applicantEmail, "applicantEmail");
        application.country = Objects.requireNonNull(country, "country");
        application.status = ApplicationStatus.DRAFT;
        application.lastCompletedStep = null;
        application.schemaVersion = CURRENT_SCHEMA_VERSION;
        application.formData = new HashMap<>();
        application.decision = null;
        application.submittedAt = null;
        application.reference = null;
        application.createdAt = Objects.requireNonNull(now, "now");
        return application;
    }

    /**
     * Moves the application to SUBMITTED, stamping the reference and submission time
     * together so the two can never disagree.
     *
     * @throws IllegalStateException if already submitted; callers wanting idempotency
     *                               should check {@link #isDraft()} first
     */
    public void submit(String reference, Instant now) {
        requireDraft("submit");
        this.reference = Objects.requireNonNull(reference, "reference");
        this.submittedAt = Objects.requireNonNull(now, "now");
        this.status = ApplicationStatus.SUBMITTED;
    }

    /**
     * Stores one validated section and, when it is the next one due, records that progress.
     *
     * <p>Whether {@code advanceTo} is set is the caller's decision because only the caller
     * holds the country's section order. This class enforces the half of the rule it can see:
     * the write happens to a draft or not at all, and section data and progress move together
     * in one call so a section can never be stored without its progress being reconsidered.
     *
     * @param values    the normalised values from layer 1, never the raw payload
     * @param advanceTo the step to record as furthest completed, or {@code null} to leave
     *                  {@code last_completed_step} exactly where it is. Backward editing
     *                  passes null; it must never drag progress back to the edited section
     */
    public void completeSection(String sectionId, Map<String, Object> values,
                                OnboardingStep advanceTo, Instant completedAt) {
        requireDraft("save a section of");
        Objects.requireNonNull(sectionId, "sectionId");
        Objects.requireNonNull(completedAt, "completedAt");

        Map<String, Object> section = new LinkedHashMap<>(values);
        section.put(COMPLETED_AT, completedAt.toString());

        // A fresh map rather than an in-place put: dirty checking on a JSON-mapped Map must
        // not depend on Hibernate noticing a mutation inside the value it already holds.
        Map<String, Object> merged = new LinkedHashMap<>(formData);
        merged.put(sectionId, section);
        this.formData = merged;

        if (advanceTo != null) {
            this.lastCompletedStep = advanceTo;
        }
    }

    /**
     * Attaches the mocked decision produced after submission.
     *
     * <p>Requires SUBMITTED, not DRAFT — this is a consequence of submitting and cannot exist
     * before it. It deliberately does not touch {@code status}: there are exactly two statuses
     * and the decision is not one of them (A1, FR6). The applicant never sees this.
     */
    public void recordDecision(Map<String, Object> decision) {
        if (status != ApplicationStatus.SUBMITTED) {
            throw new IllegalStateException(
                    "Cannot record a decision for application " + id + ": it is " + status);
        }
        this.decision = new LinkedHashMap<>(Objects.requireNonNull(decision, "decision"));
    }

    /** Email shapes nothing, so a mistyped address stays correctable while the draft lives. */
    public void changeApplicantEmail(String applicantEmail) {
        requireDraft("change the email of");
        this.applicantEmail = Objects.requireNonNull(applicantEmail, "applicantEmail");
    }

    private void requireDraft(String action) {
        if (status != ApplicationStatus.DRAFT) {
            throw new IllegalStateException(
                    "Cannot " + action + " application " + id + ": it is " + status);
        }
    }

    public boolean isDraft() {
        return status == ApplicationStatus.DRAFT;
    }

    public UUID getId() {
        return id;
    }

    public String getDraftTokenHash() {
        return draftTokenHash;
    }

    /** Present only once submitted. */
    public Optional<String> getReference() {
        return Optional.ofNullable(reference);
    }

    public String getApplicantEmail() {
        return applicantEmail;
    }

    public Country getCountry() {
        return country;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    /** Furthest section completed; empty on a fresh draft. Never a viewing position. */
    public Optional<OnboardingStep> getLastCompletedStep() {
        return Optional.ofNullable(lastCompletedStep);
    }

    public Map<String, Object> getFormData() {
        return Collections.unmodifiableMap(formData);
    }

    public Optional<Map<String, Object>> getDecision() {
        return Optional.ofNullable(decision).map(Collections::unmodifiableMap);
    }

    public Optional<Instant> getSubmittedAt() {
        return Optional.ofNullable(submittedAt);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Identifiers only. Email, form data and the token hash are never rendered (A13). */
    @Override
    public String toString() {
        return "Application[id=" + id + ", reference=" + reference + ", status=" + status
                + ", country=" + country + "]";
    }
}
