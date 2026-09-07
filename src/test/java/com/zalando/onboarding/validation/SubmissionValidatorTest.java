package com.zalando.onboarding.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.zalando.onboarding.domain.Application;
import com.zalando.onboarding.domain.Country;
import com.zalando.onboarding.flow.FlowDefinition;
import com.zalando.onboarding.flow.FlowDefinitionRepository;
import com.zalando.onboarding.flow.YamlFlowDefinitionRepository;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Layer 2. It exists because there is no authentication: a client can POST /submit against a
 * half-filled application without ever having walked the form.
 */
class SubmissionValidatorTest {

    private final FlowDefinitionRepository flows = new YamlFlowDefinitionRepository();
    private final SubmissionValidator validator = new SubmissionValidator();

    @Test
    void aFullyCompletedApplicationSubmitsCleanly() {
        assertThat(validate(Country.DE, allSections(Country.DE))).isEmpty();
    }

    @Test
    void reportsOneSectionMissingViolationPerMissingSection() {
        Map<String, Object> partial = allSections(Country.DE);
        partial.remove("taxInformation");
        partial.remove("paymentDetails");

        assertThat(validate(Country.DE, partial))
                .extracting(Violation::section, Violation::code, Violation::field)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("taxInformation", ViolationCode.SECTION_MISSING, null),
                        org.assertj.core.groups.Tuple.tuple("paymentDetails", ViolationCode.SECTION_MISSING, null));
    }

    @Test
    void anEmptyDraftReportsEverySectionOfItsCountryFlow() {
        List<Violation> violations = validate(Country.NL, new LinkedHashMap<>());

        assertThat(violations).hasSize(6);
        assertThat(violations).allSatisfy(v -> {
            assertThat(v.code()).isEqualTo(ViolationCode.SECTION_MISSING);
            assertThat(v.field()).as("a missing section names no field").isNull();
        });
        assertThat(violations).extracting(Violation::section).containsExactly(
                "personalDetails", "address", "taxInformation", "businessRegistry",
                "paymentDetails", "consent");
    }

    @Test
    void missingSectionsAreReportedAgainstTheApplicationsOwnCountryFlow() {
        // PL's registry section carries different fields from DE's, but completeness is
        // still per-section, and the section list is the country's own.
        assertThat(validate(Country.PL, new LinkedHashMap<>()))
                .extracting(Violation::section)
                .containsExactly("personalDetails", "address", "taxInformation",
                        "businessRegistry", "paymentDetails", "consent");
    }

    @Test
    void creditCheckConsentMustBeAccepted() {
        Map<String, Object> sections = allSections(Country.DE);
        Map<String, Object> consent = new LinkedHashMap<>(asMap(sections.get("consent")));
        consent.remove("creditCheck");
        sections.put("consent", consent);

        assertThat(validate(Country.DE, sections))
                .singleElement()
                .extracting(Violation::field, Violation::section, Violation::code)
                .containsExactly("creditCheck", "consent", ViolationCode.REQUIRED);
    }

    @Test
    void aCreditCheckConsentThatWasNotAcceptedIsRefused() {
        Map<String, Object> sections = allSections(Country.DE);
        Map<String, Object> consent = new LinkedHashMap<>(asMap(sections.get("consent")));
        consent.put("creditCheck", false);
        sections.put("consent", consent);

        assertThat(validate(Country.DE, sections))
                .singleElement()
                .extracting(Violation::field, Violation::code)
                .containsExactly("creditCheck", ViolationCode.REQUIRED);
    }

    @Test
    void aConsentRecordWithoutAnAcceptanceTimeIsNotAcceptance() {
        Map<String, Object> sections = allSections(Country.DE);
        Map<String, Object> consent = new LinkedHashMap<>(asMap(sections.get("consent")));
        consent.put("creditCheck", Map.of("version", "de-schufa-2026-01"));
        sections.put("consent", consent);

        assertThat(validate(Country.DE, sections))
                .singleElement()
                .extracting(Violation::field, Violation::code)
                .containsExactly("creditCheck", ViolationCode.REQUIRED);
    }

    /** Saying the same thing twice tells the applicant nothing new. */
    @Test
    void aMissingConsentSectionIsReportedOnceNotAlsoAsAMissingCreditCheck() {
        Map<String, Object> sections = allSections(Country.DE);
        sections.remove("consent");

        assertThat(validate(Country.DE, sections))
                .singleElement()
                .extracting(Violation::section, Violation::code)
                .containsExactly("consent", ViolationCode.SECTION_MISSING);
    }

    @Test
    void theRuleAppliesInEveryMarket() {
        for (Country country : Country.values()) {
            Map<String, Object> sections = allSections(country);
            Map<String, Object> consent = new LinkedHashMap<>(asMap(sections.get("consent")));
            consent.put("creditCheck", false);
            sections.put("consent", consent);

            assertThat(validate(country, sections))
                    .as("creditCheck gates decisioning in %s too", country)
                    .singleElement()
                    .extracting(Violation::field)
                    .isEqualTo("creditCheck");
        }
    }

    private List<Violation> validate(Country country, Map<String, Object> formData) {
        FlowDefinition flow = flows.findByCountry(country);
        return validator.validate(applicationWith(country, formData), flow);
    }

    /** Every section of this country's flow, completed, with all consents accepted. */
    private Map<String, Object> allSections(Country country) {
        Map<String, Object> formData = new LinkedHashMap<>();
        flows.findByCountry(country).sections().forEach(section -> {
            Map<String, Object> values = new LinkedHashMap<>();
            section.fields().forEach(field -> values.put(field.name(),
                    Map.of("version", String.valueOf(field.version()), "acceptedAt", Instant.now().toString())));
            formData.put(section.id(), values);
        });
        return formData;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    /** form_data has no writer until P4, so the blob is placed directly for this test. */
    private Application applicationWith(Country country, Map<String, Object> formData) {
        Application application = Application.newDraft(
                UUID.randomUUID(), "hash", "applicant@example.com", country, Instant.now());
        try {
            Field field = Application.class.getDeclaredField("formData");
            field.setAccessible(true);
            field.set(application, formData);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return application;
    }
}
