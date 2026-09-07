package com.zalando.onboarding.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zalando.onboarding.domain.Country;
import com.zalando.onboarding.domain.OnboardingStep;
import com.zalando.onboarding.validation.FieldValidator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A typo in configuration must stop the application, not quietly disable a rule. */
class FlowValidatorReferenceCheckTest {

    private static final FieldValidator ADULT = () -> "adult";
    private static final FieldValidator IBAN = () -> "iban";

    @Test
    void passesWhenEveryReferencedValidatorHasABean() {
        FlowValidatorReferenceCheck check = new FlowValidatorReferenceCheck(
                flowReferencing("adult"), List.of(ADULT, IBAN));

        check.afterPropertiesSet();
    }

    @Test
    void failsWithAReadableMessageWhenANameResolvesToNothing() {
        FlowValidatorReferenceCheck check = new FlowValidatorReferenceCheck(
                flowReferencing("adlut"), List.of(ADULT, IBAN));

        assertThatThrownBy(check::afterPropertiesSet)
                .isInstanceOf(FlowConfigurationException.class)
                .hasMessageContaining("'adlut'")
                .hasMessageContaining("personalDetails / dateOfBirth")
                .hasMessageContaining("Known validators: adult, iban");
    }

    @Test
    void namesTheCountrySectionAndFieldSoTheTypoIsFindable() {
        FlowValidatorReferenceCheck check = new FlowValidatorReferenceCheck(
                flowReferencing("nope"), List.of(ADULT));

        assertThatThrownBy(check::afterPropertiesSet)
                .hasMessageContaining("at DE / personalDetails / dateOfBirth")
                .hasMessageContaining("at PL / personalDetails / dateOfBirth")
                .hasMessageContaining("at NL / personalDetails / dateOfBirth");
    }

    @Test
    void failsWhenTwoBeansClaimTheSameName() {
        FlowValidatorReferenceCheck check = new FlowValidatorReferenceCheck(
                flowReferencing("adult"), List.of(ADULT, () -> "adult"));

        assertThatThrownBy(check::afterPropertiesSet)
                .isInstanceOf(FlowConfigurationException.class)
                .hasMessageContaining("Duplicate FieldValidator names")
                .hasMessageContaining("'adult'");
    }

    @Test
    void theRealConfigurationPassesItsOwnCheck() {
        FlowValidatorReferenceCheck check = new FlowValidatorReferenceCheck(
                new YamlFlowDefinitionRepository(),
                List.of(ADULT, IBAN, () -> "nip", () -> "regon", () -> "kvk", () -> "vatIdDe"));

        check.afterPropertiesSet();

        assertThat(new YamlFlowDefinitionRepository().findByCountry(Country.DE).sections()).hasSize(6);
    }

    /** Minimal in-memory flow, so the test does not depend on the shipped YAML. */
    private FlowDefinitionRepository flowReferencing(String validatorName) {
        return country -> new FlowDefinition(country, 1, List.of(
                new SectionDefinition("personalDetails", OnboardingStep.PERSONAL_DETAILS, "Personal",
                        List.of(new FieldDefinition("dateOfBirth", FieldType.DATE, true, null, null,
                                List.of(validatorName), null, null)))));
    }
}
