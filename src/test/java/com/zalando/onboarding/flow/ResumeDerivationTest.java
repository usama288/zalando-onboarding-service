package com.zalando.onboarding.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zalando.onboarding.domain.Country;
import com.zalando.onboarding.domain.OnboardingStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Resume is derived from progress, never stored. The two step types are the point: what the
 * row records ({@link OnboardingStep}, six sections) and where to send the applicant
 * ({@link ResumeStep}, those six plus REVIEW).
 */
class ResumeDerivationTest {

    private final FlowDefinitionRepository flows = new YamlFlowDefinitionRepository();

    @ParameterizedTest
    @EnumSource(Country.class)
    void nothingCompletedResumesAtTheFirstSection(Country country) {
        assertThat(flows.findByCountry(country).resumeStepAfter(null))
                .isEqualTo(ResumeStep.PERSONAL_DETAILS);
    }

    @ParameterizedTest
    @EnumSource(Country.class)
    void completingTheLastSectionResumesAtReview(Country country) {
        assertThat(flows.findByCountry(country).resumeStepAfter(OnboardingStep.CONSENT))
                .isEqualTo(ResumeStep.REVIEW);
    }

    @Test
    void otherwiseResumeIsTheSectionAfterTheOneRecorded() {
        FlowDefinition flow = flows.findByCountry(Country.DE);

        assertThat(flow.resumeStepAfter(OnboardingStep.PERSONAL_DETAILS)).isEqualTo(ResumeStep.ADDRESS);
        assertThat(flow.resumeStepAfter(OnboardingStep.ADDRESS)).isEqualTo(ResumeStep.TAX_INFORMATION);
        assertThat(flow.resumeStepAfter(OnboardingStep.TAX_INFORMATION))
                .isEqualTo(ResumeStep.BUSINESS_REGISTRY);
        assertThat(flow.resumeStepAfter(OnboardingStep.BUSINESS_REGISTRY))
                .isEqualTo(ResumeStep.PAYMENT_DETAILS);
        assertThat(flow.resumeStepAfter(OnboardingStep.PAYMENT_DETAILS)).isEqualTo(ResumeStep.CONSENT);
    }

    @Test
    void reviewIsReachableAsAResumeStepButIsNotAPersistableOne() {
        assertThat(ResumeStep.values()).contains(ResumeStep.REVIEW);
        assertThat(OnboardingStep.values())
                .as("REVIEW persists nothing, so it can never be a completed step")
                .extracting(Enum::name)
                .doesNotContain("REVIEW");
        assertThat(ResumeStep.values()).hasSize(OnboardingStep.values().length + 1);
    }

    @Test
    void derivationWalksTheCountryFlowNotTheEnumOrder() {
        FlowDefinition shortened = new FlowDefinition(Country.NL, 1, java.util.List.of(
                new SectionDefinition("personalDetails", OnboardingStep.PERSONAL_DETAILS, "One",
                        java.util.List.of()),
                new SectionDefinition("paymentDetails", OnboardingStep.PAYMENT_DETAILS, "Two",
                        java.util.List.of())));

        // ADDRESS sits between these two in the enum, but not in this flow.
        assertThat(shortened.resumeStepAfter(OnboardingStep.PERSONAL_DETAILS))
                .isEqualTo(ResumeStep.PAYMENT_DETAILS);
        assertThat(shortened.resumeStepAfter(OnboardingStep.PAYMENT_DETAILS))
                .isEqualTo(ResumeStep.REVIEW);
        assertThatThrownBy(() -> shortened.resumeStepAfter(OnboardingStep.ADDRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not part of the NL flow");
    }
}
