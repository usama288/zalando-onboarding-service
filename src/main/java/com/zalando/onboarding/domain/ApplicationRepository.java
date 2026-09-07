package com.zalando.onboarding.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {

    /** Resume lookup. The caller hashes the bearer token; the plaintext is never stored. */
    Optional<Application> findByDraftTokenHash(String draftTokenHash);

    /** Retrieval by the reference handed out at submission. */
    Optional<Application> findByReference(String reference);
}
