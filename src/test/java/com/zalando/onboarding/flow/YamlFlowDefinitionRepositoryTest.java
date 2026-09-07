package com.zalando.onboarding.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.zalando.onboarding.domain.Country;
import com.zalando.onboarding.domain.OnboardingStep;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Loads the real YAML off the classpath. No Spring context: the loader takes no
 * collaborators, so booting one would only slow the feedback down.
 */
class YamlFlowDefinitionRepositoryTest {

    private final FlowDefinitionRepository flows = new YamlFlowDefinitionRepository();

    @ParameterizedTest
    @EnumSource(Country.class)
    void everyCountryLoadsAllSixSectionsInOrder(Country country) {
        FlowDefinition flow = flows.findByCountry(country);

        assertThat(flow.country()).isEqualTo(country);
        assertThat(flow.schemaVersion()).isEqualTo(1);
        assertThat(flow.steps()).containsExactly(
                OnboardingStep.PERSONAL_DETAILS,
                OnboardingStep.ADDRESS,
                OnboardingStep.TAX_INFORMATION,
                OnboardingStep.BUSINESS_REGISTRY,
                OnboardingStep.PAYMENT_DETAILS,
                OnboardingStep.CONSENT);
    }

    @Test
    void emailIsNotAFieldBecauseItLivesOnTheApplicationRow() {
        for (Country country : Country.values()) {
            assertThat(flows.findByCountry(country).sections())
                    .flatExtracting(SectionDefinition::fields)
                    .extracting(FieldDefinition::name)
                    .doesNotContain("email", "country");
        }
    }

    /** Variation shape 1: same field, different rule. */
    @Test
    void postalCodePatternDiffersPerCountry() {
        assertThat(postalCodePattern(Country.DE)).isEqualTo("^\\d{5}$");
        assertThat(postalCodePattern(Country.PL)).isEqualTo("^\\d{2}-\\d{3}$");
        assertThat(postalCodePattern(Country.NL)).isEqualTo("^\\d{4} ?[A-Za-z]{2}$");
    }

    /** Variation shape 2: different fields entirely. */
    @Test
    void businessRegistryCarriesDifferentFieldsPerCountry() {
        assertThat(fieldNames(Country.DE, OnboardingStep.BUSINESS_REGISTRY)).containsExactly(
                "registered", "registryType", "registrationNumber", "registerCourt",
                "registeredBusinessName");
        assertThat(fieldNames(Country.PL, OnboardingStep.BUSINESS_REGISTRY))
                .containsExactly("regon", "ceidgRegistrationDate", "pkdCode");
        assertThat(fieldNames(Country.NL, OnboardingStep.BUSINESS_REGISTRY))
                .containsExactly("kvkNumber", "tradeName", "sbiCode");
    }

    /**
     * Every market asks for the same five consents. The engine can express a different item
     * set per country -- FlowVariationCapabilityTest proves it against a fixture -- but
     * these three markets do not have one, and shipped config says only what is true.
     */
    @Test
    void everyCountryAsksForTheSameFiveConsents() {
        for (Country country : Country.values()) {
            assertThat(consentFieldNames(country))
                    .as("consent item set for %s", country)
                    .containsExactly("informationConfirmed", "termsOfService", "dataProcessing",
                            "privacyNotice", "creditCheck");
            assertThat(requiredConsentNames(country))
                    .as("all five are required in %s", country)
                    .hasSize(5);
        }
    }

    /** The one real per-country difference: the bureau named in the credit-check text. */
    @Test
    void onlyTheCreditCheckVersionDiffersBetweenMarkets() {
        assertThat(consentVersion(Country.DE, "creditCheck")).isEqualTo("de-schufa-2026-01");
        assertThat(consentVersion(Country.PL, "creditCheck")).isEqualTo("pl-bik-2026-01");
        assertThat(consentVersion(Country.NL, "creditCheck")).isEqualTo("nl-bkr-2026-01");

        for (String shared : List.of("informationConfirmed", "termsOfService", "dataProcessing",
                "privacyNotice")) {
            assertThat(consentVersion(Country.DE, shared))
                    .as("%s text is the same in every market, so its version is too", shared)
                    .isEqualTo(consentVersion(Country.PL, shared))
                    .isEqualTo(consentVersion(Country.NL, shared));
        }
    }

    @Test
    void everyConsentRecordsWhichTextWasAccepted() {
        for (Country country : Country.values()) {
            assertThat(consentSection(country).fields()).allSatisfy(field -> {
                assertThat(field.type()).isEqualTo(FieldType.CONSENT);
                assertThat(field.version()).isNotBlank();
            });
        }
    }

    /** The controlling field of every conditional rule must itself be unconditionally required. */
    @Test
    void noConditionalRuleHangsOffAnOptionalController() {
        for (Country country : Country.values()) {
            for (SectionDefinition section : flows.findByCountry(country).sections()) {
                for (FieldDefinition field : section.fields()) {
                    if (field.requiredWhen() == null) {
                        continue;
                    }
                    FieldDefinition controller = section.field(field.requiredWhen().field())
                            .orElseThrow(() -> new AssertionError(
                                    "%s / %s / %s depends on a field that does not exist in the section"
                                            .formatted(country, section.id(), field.name())));
                    assertThat(controller.isAlwaysRequired())
                            .as("%s / %s / %s depends on %s, which must always be answered",
                                    country, section.id(), field.name(), controller.name())
                            .isTrue();
                }
            }
        }
    }

    @Test
    void conditionalFieldsDeclareTheirController() {
        FieldDefinition vatId = flows.findByCountry(Country.DE)
                .section(OnboardingStep.TAX_INFORMATION).orElseThrow()
                .field("vatId").orElseThrow();

        assertThat(vatId.requiredWhen()).isEqualTo(new RequiredWhen("vatRegistered", "true"));
        assertThat(vatId.isAlwaysRequired()).isFalse();
        assertThat(vatId.validators()).containsExactly("vatIdDe");
    }

    @Test
    void definitionsAreImmutable() {
        List<SectionDefinition> sections = flows.findByCountry(Country.DE).sections();

        assertThat(sections).isUnmodifiable();
        assertThat(sections.getFirst().fields()).isUnmodifiable();
    }

    private SectionDefinition consentSection(Country country) {
        return flows.findByCountry(country).section(OnboardingStep.CONSENT).orElseThrow();
    }

    private List<String> consentFieldNames(Country country) {
        return consentSection(country).fields().stream().map(FieldDefinition::name).toList();
    }

    private String consentVersion(Country country, String field) {
        return consentSection(country).field(field).orElseThrow().version();
    }

    private List<String> requiredConsentNames(Country country) {
        return consentSection(country).fields().stream()
                .filter(FieldDefinition::isAlwaysRequired)
                .map(FieldDefinition::name)
                .toList();
    }

    private String postalCodePattern(Country country) {
        return flows.findByCountry(country)
                .section(OnboardingStep.ADDRESS).orElseThrow()
                .field("postalCode").orElseThrow()
                .pattern();
    }

    private List<String> fieldNames(Country country, OnboardingStep step) {
        return flows.findByCountry(country).section(step).orElseThrow()
                .fields().stream().map(FieldDefinition::name).toList();
    }
}
