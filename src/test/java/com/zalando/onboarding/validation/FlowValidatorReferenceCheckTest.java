package com.zalando.onboarding.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zalando.onboarding.domain.Country;
import com.zalando.onboarding.domain.OnboardingStep;
import com.zalando.onboarding.flow.FieldDefinition;
import com.zalando.onboarding.flow.FieldType;
import com.zalando.onboarding.flow.FlowConfigurationException;
import com.zalando.onboarding.flow.FlowDefinition;
import com.zalando.onboarding.flow.FlowDefinitionRepository;
import com.zalando.onboarding.flow.SectionDefinition;
import com.zalando.onboarding.flow.YamlFlowDefinitionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A typo in configuration must stop the application, not quietly disable a rule. */
class FlowValidatorReferenceCheckTest {

    /** Named, does nothing. The check only cares that a name resolves to a bean. */
    private record NamedValidator(String name) implements FieldValidator {
        @Override
        public Optional<Violation> validate(String field, Object value) {
            return Optional.empty();
        }
    }

    private static final FieldValidator ADULT = new NamedValidator("adult");
    private static final FieldValidator IBAN = new NamedValidator("iban");

    @Test
    void passesWhenEveryReferencedValidatorHasABean() {
        check("adult", ADULT, IBAN).afterPropertiesSet();
    }

    @Test
    void failsWithAReadableMessageWhenANameResolvesToNothing() {
        assertThatThrownBy(() -> check("adlut", ADULT, IBAN).afterPropertiesSet())
                .isInstanceOf(FlowConfigurationException.class)
                .hasMessageContaining("'adlut'")
                .hasMessageContaining("personalDetails / dateOfBirth")
                .hasMessageContaining("Known validators: adult, iban");
    }

    @Test
    void namesTheCountrySectionAndFieldSoTheTypoIsFindable() {
        assertThatThrownBy(() -> check("nope", ADULT).afterPropertiesSet())
                .hasMessageContaining("at DE / personalDetails / dateOfBirth")
                .hasMessageContaining("at PL / personalDetails / dateOfBirth")
                .hasMessageContaining("at NL / personalDetails / dateOfBirth");
    }

    @Test
    void theShippedConfigurationPassesAgainstTheRealValidatorBeans() {
        FlowValidatorReferenceCheck check = new FlowValidatorReferenceCheck(
                new YamlFlowDefinitionRepository(), realRegistry());

        check.afterPropertiesSet();

        assertThat(realRegistry().names())
                .as("every name the shipped YAML references, resolved to a real bean")
                .containsExactly("adult", "iban", "kvk", "nip", "regon", "vatIdDe");
    }

    private static ValidatorRegistry realRegistry() {
        return new ValidatorRegistry(List.of(
                new AdultValidator(java.time.Clock.systemUTC()),
                new IbanValidator(),
                new KvkValidator(),
                new NipValidator(),
                new RegonValidator(),
                new VatIdDeValidator()));
    }

    private FlowValidatorReferenceCheck check(String referencedName, FieldValidator... registered) {
        FlowDefinitionRepository flows = country -> new FlowDefinition(country, 1, List.of(
                new SectionDefinition("personalDetails", OnboardingStep.PERSONAL_DETAILS, "Personal",
                        List.of(new FieldDefinition("dateOfBirth", "Date of birth", FieldType.DATE, true, null, null,
                                List.of(referencedName), null, null, List.of())))));
        return new FlowValidatorReferenceCheck(flows, new ValidatorRegistry(List.of(registered)));
    }
}
