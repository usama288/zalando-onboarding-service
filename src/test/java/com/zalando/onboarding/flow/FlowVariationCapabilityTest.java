package com.zalando.onboarding.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.zalando.onboarding.domain.Country;
import com.zalando.onboarding.domain.OnboardingStep;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the configuration model handles the third shape of country variation -- a different
 * item set per country -- against fixtures declared here, not against shipped config.
 *
 * <p>DE, PL and NL genuinely require the same five consents. Giving one of them an invented
 * extra consent would make the capability visible in production YAML at the cost of putting
 * a market difference that does not exist into the configuration a reviewer reads as fact.
 * The capability is real; the fixture is where it belongs, exactly as the ResumeStep test
 * uses a flow that omits ADDRESS without any shipped flow omitting it.
 */
class FlowVariationCapabilityTest {

    private static final String V = "2026-01";

    @Test
    void twoCountriesCanRequireGenuinelyDifferentConsentSets() {
        FlowDefinition lean = flowWithConsents(Country.DE,
                consent("termsOfService", true),
                consent("dataProcessing", true));

        FlowDefinition strict = flowWithConsents(Country.PL,
                consent("termsOfService", true),
                consent("dataProcessing", true),
                consent("creditCheck", true),
                consent("localRegistryDisclosure", true));

        assertThat(names(lean)).containsExactly("termsOfService", "dataProcessing");
        assertThat(names(strict)).containsExactly("termsOfService", "dataProcessing",
                "creditCheck", "localRegistryDisclosure");
        assertThat(names(strict)).containsAll(names(lean));
        assertThat(names(lean)).doesNotContain("creditCheck");
    }

    @Test
    void anOptionalConsentIsExpressibleAndStaysOutOfTheRequiredSet() {
        FlowDefinition flow = flowWithConsents(Country.NL,
                consent("termsOfService", true),
                consent("marketingCommunications", false));

        assertThat(names(flow)).hasSize(2);
        assertThat(required(flow))
                .as("an applicant who skips the optional one has still supplied everything")
                .containsExactly("termsOfService");
    }

    @Test
    void theSameConsentCanCarryADifferentVersionPerCountry() {
        FlowDefinition de = flowWithConsents(Country.DE,
                new FieldDefinition("creditCheck", "Credit check", FieldType.CONSENT, true, null, null,
                        List.of(), null, "de-schufa-2026-01", List.of()));
        FlowDefinition nl = flowWithConsents(Country.NL,
                new FieldDefinition("creditCheck", "Credit check", FieldType.CONSENT, true, null, null,
                        List.of(), null, "nl-bkr-2026-01", List.of()));

        assertThat(version(de, "creditCheck")).isNotEqualTo(version(nl, "creditCheck"));
    }

    private static FieldDefinition consent(String name, boolean required) {
        return new FieldDefinition(name, name, FieldType.CONSENT, required, null, null, List.of(), null, V, List.of());
    }

    private static FlowDefinition flowWithConsents(Country country, FieldDefinition... consents) {
        return new FlowDefinition(country, 1, List.of(
                new SectionDefinition("consent", OnboardingStep.CONSENT, "Consents", List.of(consents))));
    }

    private static List<String> names(FlowDefinition flow) {
        return consentSection(flow).fields().stream().map(FieldDefinition::name).toList();
    }

    private static List<String> required(FlowDefinition flow) {
        return consentSection(flow).fields().stream()
                .filter(FieldDefinition::isAlwaysRequired)
                .map(FieldDefinition::name)
                .toList();
    }

    private static String version(FlowDefinition flow, String field) {
        return consentSection(flow).field(field).orElseThrow().version();
    }

    private static SectionDefinition consentSection(FlowDefinition flow) {
        return flow.section(OnboardingStep.CONSENT).orElseThrow();
    }
}
