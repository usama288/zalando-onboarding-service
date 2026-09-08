package com.zalando.onboarding.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zalando.onboarding.TestcontainersConfiguration;
import com.zalando.onboarding.domain.Application;
import com.zalando.onboarding.domain.ApplicationRepository;
import com.zalando.onboarding.domain.ApplicationService;
import com.zalando.onboarding.domain.ApplicationStatus;
import com.zalando.onboarding.domain.OnboardingStep;
import com.zalando.onboarding.notification.NotificationOutboxRepository;
import com.zalando.onboarding.notification.NotificationType;
import com.zalando.onboarding.notification.OutboxNotification;
import com.zalando.onboarding.support.SecureTokenGenerator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The API and the state rules behind it, exercised end to end against a real Postgres.
 *
 * <p>Everything here goes through HTTP rather than calling the service directly, because the
 * rules being asserted are exactly the ones that exist to survive a client that ignores the
 * intended order. There is no authentication, so "the frontend would not do that" is not a
 * defence any of these tests are allowed to rely on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OnboardingApiTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ApplicationRepository applications;

    @Autowired
    private NotificationOutboxRepository outbox;

    @Autowired
    private SecureTokenGenerator tokens;

    @Autowired
    private ApplicationService service;

    // ---------------------------------------------------------------- creation and resume

    @Test
    void creatingADraftReturnsATokenAResumeUrlAndTheCountrysFlow() throws Exception {
        mvc.perform(post("/api/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateApplicationRequest(email(), "DE"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applicationId").value(notNullValue()))
                .andExpect(jsonPath("$.draftToken").value(notNullValue()))
                .andExpect(jsonPath("$.resumeUrl").value(startsWith("http://localhost:8080/#/resume/")))
                .andExpect(jsonPath("$.flow.country").value("DE"))
                .andExpect(jsonPath("$.flow.sections", hasSize(6)));
    }

    @Test
    void aFreshDraftHasNoProgressAndResumesAtTheFirstSection() throws Exception {
        String token = createDraft("DE");

        mvc.perform(get("/api/applications/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.country").value("DE"))
                .andExpect(jsonPath("$.lastCompletedStep").doesNotExist())
                .andExpect(jsonPath("$.resumeStep").value("PERSONAL_DETAILS"))
                .andExpect(jsonPath("$.formData").isEmpty());
    }

    /** Only the hash is stored, so a database dump yields no working resume links. */
    @Test
    void thePlaintextDraftTokenIsNeverStored() throws Exception {
        String email = email();
        String token = createDraft("DE", email);

        Application stored = applications.findByDraftTokenHash(tokens.hashDraftToken(token))
                .orElseThrow();

        assertThat(stored.getDraftTokenHash()).isNotEqualTo(token);
        assertThat(applications.findAll())
                .noneSatisfy(app -> assertThat(app.getDraftTokenHash()).isEqualTo(token));
    }

    /** The link has to reach the applicant somehow; the outbox is where it goes (A18). */
    @Test
    void creatingADraftRecordsAResumeLinkInTheOutbox() throws Exception {
        String email = email();
        MvcResult result = createDraftResult("PL", email);
        UUID applicationId = UUID.fromString(field(result, "applicationId"));
        String resumeUrl = field(result, "resumeUrl");

        List<OutboxNotification> recorded = outbox.findByApplicationIdOrderByCreatedAtDesc(applicationId);

        assertThat(recorded).singleElement().satisfies(notification -> {
            assertThat(notification.getType()).isEqualTo(NotificationType.RESUME_LINK);
            assertThat(notification.getRecipient()).isEqualTo(email);
            assertThat(notification.getPayload()).containsEntry("resumeUrl", resumeUrl);
        });
    }

    @Test
    void anEmailIsNormalisedAndAMalformedOneIsRefused() throws Exception {
        String token = createDraft("DE", "  Ada.Lovelace@Example.COM  ");

        mvc.perform(get("/api/applications/{token}", token))
                .andExpect(jsonPath("$.email").value("ada.lovelace@example.com"));

        mvc.perform(post("/api/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateApplicationRequest("not-an-email", "DE"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.violations[0].field").value("email"))
                .andExpect(jsonPath("$.violations[0].code").value("PATTERN_INVALID"));
    }

    /** A19: entering an email always starts a new draft; nothing is ever looked up by it. */
    @Test
    void twoDraftsWithTheSameEmailAreBothAllowedAndAreDifferentApplications() throws Exception {
        String email = email();

        String first = createDraft("DE", email);
        String second = createDraft("DE", email);

        assertThat(first).isNotEqualTo(second);
        assertThat(applications.findByDraftTokenHash(tokens.hashDraftToken(first)).orElseThrow().getId())
                .isNotEqualTo(applications.findByDraftTokenHash(tokens.hashDraftToken(second))
                        .orElseThrow().getId());
    }

    @Test
    void anUnknownDraftTokenIs404() throws Exception {
        mvc.perform(get("/api/applications/{token}", "not-a-real-token"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.requestId").value(notNullValue()))
                .andExpect(jsonPath("$.violations").isArray());
    }

    // ---------------------------------------------------------------------- forward gating

    @Test
    void forwardGatingBlocksSkippingAStep() throws Exception {
        String token = createDraft("DE");

        // Straight at step 3 without having completed steps 1 or 2.
        mvc.perform(saveSection(token, "taxInformation", germanTax()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.violations[0].section").value("taxInformation"))
                .andExpect(jsonPath("$.violations[0].code").value("SECTION_NOT_REACHABLE"));

        // And nothing was written on the way past.
        mvc.perform(get("/api/applications/{token}", token))
                .andExpect(jsonPath("$.formData").isEmpty())
                .andExpect(jsonPath("$.lastCompletedStep").doesNotExist());
    }

    @Test
    void gatingAllowsExactlyTheNextSectionAndNoFurther() throws Exception {
        String token = createDraft("DE");

        mvc.perform(saveSection(token, "personalDetails", personalDetails()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastCompletedStep").value("PERSONAL_DETAILS"))
                .andExpect(jsonPath("$.resumeStep").value("ADDRESS"));

        // One past the next is still blocked.
        mvc.perform(saveSection(token, "businessRegistry", germanRegistry()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.violations[0].code").value("SECTION_NOT_REACHABLE"));
    }

    @Test
    void aSectionThatIsNotInThisCountrysFlowIsRefusedAsUnknown() throws Exception {
        String token = createDraft("DE");

        mvc.perform(saveSection(token, "kvk", Map.of("kvkNumber", "12345678")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].section").value("kvk"))
                .andExpect(jsonPath("$.violations[0].code").value("SECTION_UNKNOWN"));
    }

    // --------------------------------------------------------------------- backward editing

    @Test
    void backwardEditingOfACompletedSectionIsAllowed() throws Exception {
        String token = createDraft("DE");
        completeThrough("DE", token, "personalDetails", "address", "taxInformation");

        Map<String, Object> corrected = personalDetails();
        corrected.put("firstName", "Augusta");

        mvc.perform(saveSection(token, "personalDetails", corrected))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedSection.id").value("personalDetails"))
                .andExpect(jsonPath("$.savedSection.data.firstName").value("Augusta"));

        mvc.perform(get("/api/applications/{token}", token))
                .andExpect(jsonPath("$.formData.personalDetails.firstName").value("Augusta"))
                .andExpect(jsonPath("$.formData.taxInformation").exists());
    }

    @Test
    void lastCompletedStepNeverMovesBackward() throws Exception {
        String token = createDraft("DE");
        completeThrough("DE", token, "personalDetails", "address", "taxInformation");

        assertThat(reload(token).getLastCompletedStep()).contains(OnboardingStep.TAX_INFORMATION);

        // Re-saving the first section is a correction, not a regression.
        mvc.perform(saveSection(token, "personalDetails", personalDetails()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastCompletedStep").value("TAX_INFORMATION"))
                .andExpect(jsonPath("$.resumeStep").value("BUSINESS_REGISTRY"));

        // Re-saving the furthest one is not a regression either.
        mvc.perform(saveSection(token, "taxInformation", germanTax()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastCompletedStep").value("TAX_INFORMATION"));

        assertThat(reload(token).getLastCompletedStep()).contains(OnboardingStep.TAX_INFORMATION);
    }

    // --------------------------------------------------------------------- layer 1 on write

    @Test
    void aSectionThatFailsFieldValidationPersistsNothing() throws Exception {
        String token = createDraft("DE");

        Map<String, Object> invalid = personalDetails();
        invalid.put("dateOfBirth", "2020-01-01");

        mvc.perform(saveSection(token, "personalDetails", invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("dateOfBirth"))
                .andExpect(jsonPath("$.violations[0].code").value("NOT_ADULT"))
                .andExpect(jsonPath("$.violations[0].section").value("personalDetails"));

        Application stored = reload(token);
        assertThat(stored.getFormData()).isEmpty();
        assertThat(stored.getLastCompletedStep()).isEmpty();
    }

    @Test
    void whatIsStoredIsTheNormalisedValueNotTheTypedOne() throws Exception {
        String token = createDraft("PL");
        completeThrough("PL", token, "personalDetails", "address");

        mvc.perform(saveSection(token, "taxInformation", polishTax("856-734-62-15")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedSection.data.nip").value("8567346215"))
                .andExpect(jsonPath("$.savedSection.data.completedAt").value(notNullValue()));
    }

    // --------------------------------------------------------------------------- submission

    @Test
    void submittingAHalfFilledDraftReportsSectionMissingPerMissingSection() throws Exception {
        String token = createDraft("DE");
        completeThrough("DE", token, "personalDetails");

        mvc.perform(post("/api/applications/{token}/submit", token))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.violations", hasSize(5)))
                .andExpect(jsonPath("$.violations[*].code", everyItem(is("SECTION_MISSING"))))
                .andExpect(jsonPath("$.violations[*].section", containsInAnyOrder(
                        "address", "taxInformation", "businessRegistry", "paymentDetails", "consent")));

        assertThat(reload(token).getStatus()).isEqualTo(ApplicationStatus.DRAFT);
    }

    /** Layer 2 exists because there is no auth: submit can be called without walking the form. */
    @Test
    void submittingAnUntouchedDraftReportsEverySectionOfItsFlow() throws Exception {
        String token = createDraft("NL");

        mvc.perform(post("/api/applications/{token}/submit", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations", hasSize(6)))
                .andExpect(jsonPath("$.violations[*].code", everyItem(is("SECTION_MISSING"))));
    }

    @Test
    void submittingTwiceReturnsOneReference() throws Exception {
        String token = completedDraft("DE");

        MvcResult first = mvc.perform(post("/api/applications/{token}/submit", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.reference").value(startsWith("ONB-")))
                .andExpect(jsonPath("$.submittedAt").value(notNullValue()))
                .andReturn();

        MvcResult second = mvc.perform(post("/api/applications/{token}/submit", token))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(field(second, "reference")).isEqualTo(field(first, "reference"));
        assertThat(field(second, "submittedAt")).isEqualTo(field(first, "submittedAt"));

        assertThat(applications.findByReference(field(first, "reference"))).isPresent();
    }

    @Test
    void writingToASubmittedApplicationIs409() throws Exception {
        String token = completedDraft("DE");
        mvc.perform(post("/api/applications/{token}/submit", token)).andExpect(status().isOk());

        mvc.perform(saveSection(token, "personalDetails", personalDetails()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.violations[0].code").value("APPLICATION_ALREADY_SUBMITTED"));
    }

    @Test
    void aSubmittedApplicationIsRetrievableByReference() throws Exception {
        String email = email();
        String token = completedDraft("DE", email);
        String reference = field(mvc.perform(post("/api/applications/{token}/submit", token))
                .andReturn(), "reference");

        mvc.perform(get("/api/applications/reference/{reference}", reference))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(reference))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.country").value("DE"))
                .andExpect(jsonPath("$.formData.consent").exists());
    }

    @Test
    void anUnknownReferenceIs404() throws Exception {
        mvc.perform(get("/api/applications/reference/{reference}", "ONB-0000000000000000"))
                .andExpect(status().isNotFound());
    }

    /** A7: one submitted application per email; duplicate drafts are fine, duplicate submits are not. */
    @Test
    void asecondSubmissionWithTheSameEmailIsRefused() throws Exception {
        String email = email();
        mvc.perform(post("/api/applications/{token}/submit", completedDraft("DE", email)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/applications/{token}/submit", completedDraft("DE", email)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.violations[0].code").value("EMAIL_ALREADY_SUBMITTED"));
    }

    /**
     * The sequential idempotency test above never reaches the conditional UPDATE, because the
     * second call sees a submitted application and returns early. This one does: two threads
     * both hold a DRAFT, both pass layer 2, and both run the UPDATE. Exactly one can match a
     * row, and the loser has to come back with the winner's reference rather than its own.
     */
    @Test
    void twoSimultaneousSubmitsMintOneReference() throws Exception {
        String token = completedDraft("DE");

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> attempts = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            attempts.add(pool.submit(() -> {
                startTogether.await(20, TimeUnit.SECONDS);
                return service.submit(token).getReference().orElseThrow();
            }));
        }
        pool.shutdown();

        Set<String> references = new HashSet<>();
        for (Future<String> attempt : attempts) {
            references.add(attempt.get(30, TimeUnit.SECONDS));
        }

        assertThat(references).as("both callers see the same, single reference").hasSize(1);

        String reference = references.iterator().next();
        assertThat(reload(token).getReference()).contains(reference);

        // One reference, and one row carrying it. A second row would mean the race produced a
        // whole second application rather than a second reference on the same one.
        assertThat(applications.findAll())
                .filteredOn(app -> app.getReference().filter(reference::equals).isPresent())
                .as("exactly one row holds the reference")
                .hasSize(1);
        assertThat(reload(token).getStatus()).isEqualTo(ApplicationStatus.SUBMITTED);
    }

    /**
     * The mocked decision runs at submit and is stored — and is visible to nobody. It must not
     * appear in either read model, and it must not have moved the status: there are two
     * statuses and the decision is not one of them (A1, FR6).
     */
    @Test
    void submissionRecordsADecisionThatIsNeverReturnedAndNeverChangesStatus() throws Exception {
        String token = completedDraft("DE");
        String reference = field(mvc.perform(post("/api/applications/{token}/submit", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn(), "reference");

        Application stored = reload(token);
        assertThat(stored.getStatus()).isEqualTo(ApplicationStatus.SUBMITTED);
        assertThat(stored.getDecision())
                .as("a decision was recorded")
                .isPresent()
                .get(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKeys("identityConfidence", "debtFlags", "finalDecision", "decidedAt")
                .containsEntry("finalDecision", "APPROVE");

        mvc.perform(get("/api/applications/{token}", token))
                .andExpect(jsonPath("$.decision").doesNotExist());
        mvc.perform(get("/api/applications/reference/{reference}", reference))
                .andExpect(jsonPath("$.decision").doesNotExist());
    }

    /** B4: the token holder may already read every field, so the reference is not withheld. */
    @Test
    void aSubmittedApplicationLoadedByItsTokenCarriesItsReference() throws Exception {
        String token = completedDraft("DE");
        String reference = field(mvc.perform(post("/api/applications/{token}/submit", token))
                .andReturn(), "reference");

        mvc.perform(get("/api/applications/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.reference").value(reference))
                .andExpect(jsonPath("$.submittedAt").value(notNullValue()));
    }

    /** A draft has neither, and they are omitted rather than sent as null. */
    @Test
    void aDraftCarriesNoReferenceOrSubmissionTime() throws Exception {
        mvc.perform(get("/api/applications/{token}", createDraft("DE")))
                .andExpect(jsonPath("$.reference").doesNotExist())
                .andExpect(jsonPath("$.submittedAt").doesNotExist());
    }

    /**
     * B8: the draft token is a bearer credential and a problem body is the thing people paste
     * into support tickets. `instance` names the route, never the URI that carried the token.
     */
    @Test
    void aProblemBodyNamesTheRouteAndNeverEchoesTheDraftToken() throws Exception {
        String token = createDraft("DE");

        String body = mvc.perform(saveSection(token, "personalDetails", Map.of("firstName", "")))
                .andExpect(status().isBadRequest())
                // Percent-encoded because a URI template is not a valid URI.
                .andExpect(jsonPath("$.instance")
                        .value("/api/applications/%7BdraftToken%7D/sections/%7BsectionId%7D"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("no problem body may contain the token").doesNotContain(token);
    }

    @Test
    void aNotFoundBodyDoesNotEchoTheTokenThatMissedEither() throws Exception {
        String body = mvc.perform(get("/api/applications/{token}", "a-token-that-does-not-exist"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("a-token-that-does-not-exist");
    }

    /** B6: internal reading helpers are not part of the flow contract. */
    @Test
    void theFlowContractCarriesNoDerivedAccessors() throws Exception {
        String body = mvc.perform(get("/api/flows/{country}", "DE"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("alwaysRequired").doesNotContain("\"choice\"");
    }

    /**
     * B2, end to end: the client says whether a box was ticked. The version identifying the
     * text agreed to, and the moment of agreement, are the server's to write.
     */
    @Test
    void theStoredConsentCarriesTheFlowsVersionAndAServerTimestamp() throws Exception {
        String token = createDraft("DE");
        completeThrough("DE", token, "personalDetails", "address", "taxInformation",
                "businessRegistry", "paymentDetails");

        Map<String, Object> forged = new LinkedHashMap<>(consents("de-schufa-2026-01"));
        forged.put("creditCheck", Map.of(
                "version", "not-a-real-version", "acceptedAt", "2099-01-01T00:00:00Z"));

        mvc.perform(saveSection(token, "consent", forged))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savedSection.data.creditCheck.version")
                        .value("de-schufa-2026-01"))
                .andExpect(jsonPath("$.savedSection.data.creditCheck.acceptedAt")
                        .value(org.hamcrest.Matchers.startsWith("20")))
                .andExpect(jsonPath("$.savedSection.data.creditCheck.acceptedAt")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.startsWith("2099"))));
    }

    // --------------------------------------------------------------------------- flow and ids

    @Test
    void theFlowEndpointServesEachCountrysDefinition() throws Exception {
        mvc.perform(get("/api/flows/{country}", "PL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.country").value("PL"))
                .andExpect(jsonPath("$.sections[2].fields[0].name").value("nip"));

        mvc.perform(get("/api/flows/{country}", "nl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.country").value("NL"));

        mvc.perform(get("/api/flows/{country}", "FR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].code").value("COUNTRY_UNKNOWN"));
    }

    @Test
    void everyResponseCarriesARequestIdAndAnInboundOneIsHonoured() throws Exception {
        mvc.perform(get("/api/flows/{country}", "DE"))
                .andExpect(header().exists("X-Request-Id"));

        mvc.perform(get("/api/applications/{token}", "missing").header("X-Request-Id", "trace-42"))
                .andExpect(header().string("X-Request-Id", "trace-42"))
                .andExpect(jsonPath("$.requestId").value("trace-42"));

        // A header that could forge a log line is replaced rather than echoed.
        mvc.perform(get("/api/applications/{token}", "missing")
                        .header("X-Request-Id", "bad\nid ERROR everything is fine"))
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.not("bad")))
                .andExpect(jsonPath("$.requestId").value(notNullValue()));
    }

    @Test
    void anUnreadableBodyStillComesBackAsTheSameProblemShape() throws Exception {
        mvc.perform(post("/api/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.requestId").value(notNullValue()))
                .andExpect(jsonPath("$.violations[0].code").value("MALFORMED_REQUEST"));
    }

    /** Dev-profile only: the outbox payload is a resume link, which is a bearer credential. */
    @Test
    void theOutboxEndpointIsNotExposedWithoutTheDevProfile() throws Exception {
        mvc.perform(get("/api/dev/outbox")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------ helpers

    private String email() {
        return "applicant-" + UNIQUE.incrementAndGet() + "-" + UUID.randomUUID() + "@example.com";
    }

    private String createDraft(String country) throws Exception {
        return createDraft(country, email());
    }

    private String createDraft(String country, String email) throws Exception {
        return field(createDraftResult(country, email), "draftToken");
    }

    private MvcResult createDraftResult(String country, String email) throws Exception {
        return mvc.perform(post("/api/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateApplicationRequest(email, country))))
                .andExpect(status().isCreated())
                .andReturn();
    }

    /** A DE draft with every section completed, ready to submit. */
    private String completedDraft(String country) throws Exception {
        return completedDraft(country, email());
    }

    private String completedDraft(String country, String email) throws Exception {
        String token = createDraft(country, email);
        completeThrough(country, token, "personalDetails", "address", "taxInformation",
                "businessRegistry", "paymentDetails", "consent");
        return token;
    }

    private void completeThrough(String country, String token, String... sectionIds) throws Exception {
        for (String sectionId : sectionIds) {
            mvc.perform(saveSection(token, sectionId, payloadFor(country, sectionId)))
                    .andExpect(status().isOk());
        }
    }

    private org.springframework.test.web.servlet.RequestBuilder saveSection(
            String token, String sectionId, Map<String, Object> payload) throws Exception {
        return put("/api/applications/{token}/sections/{sectionId}", token, sectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload));
    }

    private String field(MvcResult result, String name) throws Exception {
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        return String.valueOf(body.get(name));
    }

    private Application reload(String token) {
        return applications.findByDraftTokenHash(tokens.hashDraftToken(token)).orElseThrow();
    }

    /**
     * A valid payload for one section of one country's flow.
     *
     * <p>Built per country rather than shared, because sharing one payload across the three
     * would silently prove the opposite of what these tests are for: a DE postcode must fail
     * in PL, and a fixture that papers over that difference is a fixture that would still
     * pass if country variation stopped working.
     */
    private Map<String, Object> payloadFor(String country, String sectionId) {
        return switch (sectionId) {
            case "personalDetails" -> personalDetails();
            case "address" -> address(switch (country) {
                case "DE" -> "10115";
                case "PL" -> "00-950";
                case "NL" -> "1012 AB";
                default -> throw new IllegalArgumentException(country);
            });
            case "taxInformation" -> switch (country) {
                case "DE" -> germanTax();
                case "PL" -> polishTax("8567346215");
                case "NL" -> dutchTax();
                default -> throw new IllegalArgumentException(country);
            };
            case "businessRegistry" -> switch (country) {
                case "DE" -> germanRegistry();
                case "PL" -> polishRegistry();
                case "NL" -> dutchRegistry();
                default -> throw new IllegalArgumentException(country);
            };
            case "paymentDetails" -> paymentDetails();
            case "consent" -> consents(switch (country) {
                case "DE" -> "de-schufa-2026-01";
                case "PL" -> "pl-bik-2026-01";
                case "NL" -> "nl-bkr-2026-01";
                default -> throw new IllegalArgumentException(country);
            });
            default -> throw new IllegalArgumentException("No test payload for " + sectionId);
        };
    }

    private Map<String, Object> personalDetails() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("firstName", "Ada");
        values.put("lastName", "Lovelace");
        values.put("dateOfBirth", "1990-05-17");
        values.put("nationality", "DE");
        return values;
    }

    private Map<String, Object> address(String postalCode) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("street", "Hauptstrasse");
        values.put("houseNumber", "12a");
        values.put("postalCode", postalCode);
        values.put("city", "Berlin");
        return values;
    }

    private Map<String, Object> germanTax() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("taxNumber", "12345678901");
        values.put("vatRegistered", false);
        return values;
    }

    private Map<String, Object> polishTax(String nip) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("nip", nip);
        values.put("vatRegistered", false);
        return values;
    }

    private Map<String, Object> dutchTax() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("vatRegistered", false);
        return values;
    }

    private Map<String, Object> germanRegistry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("registered", false);
        return values;
    }

    private Map<String, Object> polishRegistry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("regon", "192598184");
        values.put("ceidgRegistrationDate", "2020-01-01");
        values.put("pkdCode", "62.01.Z");
        return values;
    }

    private Map<String, Object> dutchRegistry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kvkNumber", "12345678");
        values.put("tradeName", "Ada BV");
        values.put("sbiCode", "6201");
        return values;
    }

    private Map<String, Object> paymentDetails() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("accountHolder", "Ada Lovelace");
        values.put("iban", "DE68 2105 0170 0012 3456 78");
        return values;
    }

    private Map<String, Object> consents(String creditCheckVersion) {
        Map<String, Object> values = new LinkedHashMap<>();
        List.of("informationConfirmed", "termsOfService", "dataProcessing", "privacyNotice")
                .forEach(name -> values.put(name, accepted("2026-01")));
        values.put("creditCheck", accepted(creditCheckVersion));
        return values;
    }

    private Map<String, Object> accepted(String version) {
        return Map.of("version", version, "acceptedAt", "2026-09-08T10:00:00Z");
    }
}
