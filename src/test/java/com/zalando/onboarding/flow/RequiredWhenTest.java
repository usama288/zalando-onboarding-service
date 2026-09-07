package com.zalando.onboarding.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The comparison contract, executable. Layer 1 and layer 2 both answer "is this field
 * required" from this one method, so the two can never disagree about completeness.
 */
class RequiredWhenTest {

    private final RequiredWhen vatRegistered = new RequiredWhen("vatRegistered", "true");

    @Test
    void trueMatchesAndMakesTheDependentFieldRequired() {
        assertThat(vatRegistered.matches(true)).isTrue();
        assertThat(vatRegistered.matches("true")).isTrue();
    }

    @Test
    void falseDoesNotMatchSoVatIdStaysOptional() {
        assertThat(vatRegistered.matches(false))
                .as("answering no must not demand a VAT id")
                .isFalse();
        assertThat(vatRegistered.matches("false")).isFalse();
    }

    @Test
    void anAbsentFieldNeverMatches() {
        Map<String, Object> section = new HashMap<>();

        assertThat(vatRegistered.matches(section.get("vatRegistered")))
                .as("absent from the payload entirely")
                .isFalse();

        section.put("vatRegistered", null);
        assertThat(vatRegistered.matches(section.get("vatRegistered")))
                .as("present but null")
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True", "  true  ", "\ttrue\n"})
    void coercionIsCaseInsensitiveAndTrimmed(String observed) {
        assertThat(vatRegistered.matches(observed)).isTrue();
    }

    @Test
    void unrelatedValuesDoNotMatch() {
        assertThat(vatRegistered.matches("yes")).isFalse();
        assertThat(vatRegistered.matches(1)).isFalse();
        assertThat(vatRegistered.matches("")).isFalse();
    }

    @Test
    void theContractHoldsForNonBooleanControllers() {
        RequiredWhen registryIsHrb = new RequiredWhen("registryType", "HRB");

        assertThat(registryIsHrb.matches("hrb")).isTrue();
        assertThat(registryIsHrb.matches("HRA")).isFalse();
        assertThat(registryIsHrb.matches(null)).isFalse();
    }
}
