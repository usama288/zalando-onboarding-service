package com.zalando.onboarding.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.zalando.onboarding.TestcontainersConfiguration;
import com.zalando.onboarding.domain.Application;
import com.zalando.onboarding.domain.ApplicationRepository;
import com.zalando.onboarding.domain.Country;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Covers the path the schema tests could not reach: a real generated token, hashed, stored,
 * and looked back up. A generator that silently returned "" would satisfy every CHECK
 * constraint in V1 and every mapping assertion in the context test, so the properties that
 * make the token a usable credential are asserted here directly.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SecureTokenGeneratorTest {

    @Autowired
    private SecureTokenGenerator generator;

    @Autowired
    private ApplicationRepository applications;

    @Test
    void draftTokenCarriesAtLeast128BitsOfEntropy() {
        String token = generator.newDraftToken();

        // Measure the randomness, not the string length: decode back to the bytes that
        // SecureRandom actually produced. 43 base64url chars of a constant would be 0 bits.
        byte[] raw = Base64.getUrlDecoder().decode(token);
        assertThat(raw.length * 8)
                .as("bits of entropy in the draft token")
                .isGreaterThanOrEqualTo(128);
    }

    @Test
    void draftTokenIsUrlSafe() {
        String token = generator.newDraftToken();

        assertThat(token)
                .as("must drop into a resume link without escaping")
                .isNotEmpty()
                .matches("[A-Za-z0-9_-]+");
    }

    @Test
    void successiveTokensDiffer() {
        String first = generator.newDraftToken();
        String second = generator.newDraftToken();

        assertThat(first).isNotEqualTo(second);

        // Two calls differing could still be a counter. Take a batch and require all distinct.
        Set<String> batch = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            batch.add(generator.newDraftToken());
        }
        assertThat(batch).hasSize(500);
    }

    @Test
    void tokenIsFoundByItsHashAndThePlaintextIsNeverStored() {
        String token = generator.newDraftToken();
        String hash = generator.hashDraftToken(token);
        UUID id = UUID.randomUUID();

        applications.saveAndFlush(
                Application.newDraft(id, hash, "applicant@example.com", Country.DE, Instant.now()));

        assertThat(applications.findByDraftTokenHash(hash))
                .as("resume looks the row up by hash")
                .get()
                .extracting(Application::getId)
                .isEqualTo(id);

        assertThat(applications.findByDraftTokenHash(token))
                .as("the plaintext token is not what is stored, so it must not match a row")
                .isEmpty();

        assertThat(applications.findById(id).orElseThrow().getDraftTokenHash())
                .as("what landed in the column")
                .isEqualTo(hash)
                .isNotEqualTo(token);
    }

    @Test
    void hashingIsDeterministicSha256() {
        String token = generator.newDraftToken();

        assertThat(generator.hashDraftToken(token))
                .as("64 hex chars, and stable, or lookup by hash could never work")
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isEqualTo(generator.hashDraftToken(token));
    }

    @Test
    void referenceIsUnguessableAndFitsTheColumn() {
        Set<String> references = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            references.add(generator.newReference());
        }

        assertThat(references).hasSize(500);
        assertThat(references).allSatisfy(reference -> assertThat(reference)
                .hasSizeLessThanOrEqualTo(24)
                .matches("ONB-[0-9A-HJKMNP-TV-Z]{16}"));
    }
}
