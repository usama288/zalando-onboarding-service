package com.zalando.onboarding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.zalando.onboarding.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins the ownership decision for {@code updated_at}: Hibernate's {@code @UpdateTimestamp},
 * with no trigger and no assignment in the entity. DEFAULT NOW() alone would leave the value
 * frozen at insert time, so this asserts the column actually moves on UPDATE.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ApplicationTimestampsTest {

    @Autowired
    private ApplicationRepository applications;

    @Autowired
    private EntityManager entityManager;

    @Test
    void updatedAtMovesOnUpdateWhileCreatedAtHoldsStill() throws InterruptedException {
        // Backdated so "created" and "updated" can never be confused for one another.
        Instant createdAt = Instant.now().minus(1, ChronoUnit.HOURS);
        UUID id = UUID.randomUUID();

        Application draft = applications.saveAndFlush(
                Application.newDraft(id, "hash-" + id, "applicant@example.com", Country.DE, createdAt));

        Instant afterInsert = draft.getUpdatedAt();
        assertThat(afterInsert)
                .as("@UpdateTimestamp stamps on insert too, so the column is never null")
                .isNotNull()
                .isAfter(createdAt);

        Thread.sleep(10);
        draft.changeApplicantEmail("corrected@example.com");
        applications.flush();
        entityManager.refresh(draft);

        assertThat(draft.getUpdatedAt())
                .as("moved on UPDATE, which DEFAULT NOW() would not have done")
                .isAfter(afterInsert);
        assertThat(draft.getCreatedAt())
                .as("immutable")
                .isEqualTo(createdAt);
    }
}
