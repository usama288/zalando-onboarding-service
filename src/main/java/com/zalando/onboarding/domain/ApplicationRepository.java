package com.zalando.onboarding.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {

    /** Resume lookup. The caller hashes the bearer token; the plaintext is never stored. */
    Optional<Application> findByDraftTokenHash(String draftTokenHash);

    /** Retrieval by the reference handed out at submission. */
    Optional<Application> findByReference(String reference);

    /**
     * Email uniqueness is scoped to submitted applications only; duplicate drafts are fine (A7).
     *
     * <p>Excluding {@code id} is not defensive tidiness. Two simultaneous submits of the SAME
     * application race here: the loser can reach this check after the winner has committed,
     * and without the exclusion it would refuse an application for colliding with itself,
     * turning an idempotent double-click into a 409.
     */
    boolean existsByApplicantEmailAndStatusAndIdNot(String applicantEmail, ApplicationStatus status,
                                                    UUID id);

    /**
     * The submit transition, expressed as a single conditional UPDATE so that two concurrent
     * submits of one draft cannot both win (A10).
     *
     * <p>rows = 1 means this call performed the transition and the reference it passed in is
     * the application's reference. rows = 0 means the row was no longer DRAFT, so somebody
     * else got there first and the reference passed in was never stored — the caller re-reads
     * and returns the existing one. A read-then-write pair could not tell those apart.
     *
     * <p>{@code updated_at} and {@code row_version} are set by hand here because this
     * statement goes around JPA: {@code @UpdateTimestamp} and {@code @Version} only fire on a
     * Hibernate flush, and leaving them behind would strand both columns on the one row
     * transition that matters most.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE applications
               SET status = 'SUBMITTED',
                   reference = :reference,
                   submitted_at = :now,
                   updated_at = :now,
                   row_version = row_version + 1
             WHERE id = :id
               AND status = 'DRAFT'
            """, nativeQuery = true)
    int markSubmitted(@Param("id") UUID id, @Param("reference") String reference,
                      @Param("now") Instant now);
}
