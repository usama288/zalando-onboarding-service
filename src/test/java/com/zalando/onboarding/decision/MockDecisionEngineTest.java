package com.zalando.onboarding.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.zalando.onboarding.domain.Application;
import com.zalando.onboarding.domain.Country;
import com.zalando.onboarding.domain.OnboardingStep;
import com.zalando.onboarding.flow.FlowDefinitionRepository;
import com.zalando.onboarding.flow.YamlFlowDefinitionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The mocked checks, one test per branch.
 *
 * <p>These are only worth writing because the engine is deterministic. An implementation
 * using {@code Random} could pass all of them and still be wrong in production, which is why
 * the last test here asserts determinism directly rather than assuming it.
 */
class MockDecisionEngineTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");

    private final FlowDefinitionRepository flows = new YamlFlowDefinitionRepository();

    private final MockDecisionEngine engine = new MockDecisionEngine(
            flows,
            new DecisionProperties(List.of("99999999999", "7777777777"), 70),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aConsistentApplicationIsApproved() {
        Decision decision = engine.decide(application(Country.DE, germanForm()));

        assertThat(decision.finalDecision()).isEqualTo(FinalDecision.APPROVE);
        assertThat(decision.identityConfidence()).isEqualTo(100);
        assertThat(decision.debtFlags()).isEmpty();
        assertThat(decision.decidedAt()).isEqualTo(NOW);
    }

    /** A payout account naming somebody else: legitimate sometimes, worth a human always. */
    @Test
    void aPayoutAccountInSomebodyElsesNameIsReferred() {
        Map<String, Object> form = germanForm();
        form.put("paymentDetails", section(Map.of(
                "accountHolder", "Charles Babbage", "iban", "DE68210501700012345678")));

        Decision decision = engine.decide(application(Country.DE, form));

        assertThat(decision.finalDecision()).isEqualTo(FinalDecision.REFER);
        assertThat(decision.identityConfidence()).isEqualTo(60);
        assertThat(decision.debtFlags()).isEmpty();
    }

    /**
     * A12 is explicit that a cross-border IBAN is legitimate under SEPA, so on its own it
     * lowers confidence without crossing the referral threshold. It only matters combined.
     */
    @Test
    void aForeignIbanAloneLowersConfidenceWithoutReferring() {
        Map<String, Object> form = germanForm();
        form.put("paymentDetails", section(Map.of(
                "accountHolder", "Ada Lovelace", "iban", "FR1420041010050500013M02606")));

        Decision decision = engine.decide(application(Country.DE, form));

        assertThat(decision.identityConfidence()).isEqualTo(75);
        assertThat(decision.finalDecision()).isEqualTo(FinalDecision.APPROVE);
    }

    @Test
    void aForeignIbanInSomebodyElsesNameIsReferred() {
        Map<String, Object> form = germanForm();
        form.put("paymentDetails", section(Map.of(
                "accountHolder", "Charles Babbage", "iban", "FR1420041010050500013M02606")));

        Decision decision = engine.decide(application(Country.DE, form));

        assertThat(decision.identityConfidence()).isEqualTo(35);
        assertThat(decision.finalDecision()).isEqualTo(FinalDecision.REFER);
    }

    @Test
    void aTaxNumberOnTheDemoDebtRegisterIsDeclined() {
        Map<String, Object> form = germanForm();
        form.put("taxInformation", section(Map.of("taxNumber", "99999999999", "vatRegistered", false)));

        Decision decision = engine.decide(application(Country.DE, form));

        assertThat(decision.finalDecision()).isEqualTo(FinalDecision.DECLINE);
        assertThat(decision.debtFlags()).containsExactly(Decision.DEBT_REGISTER_MATCH);
    }

    /** A hard flag decides on its own; full confidence must not talk it out of a DECLINE. */
    @Test
    void aDebtFlagOutranksPerfectConfidence() {
        Map<String, Object> form = germanForm();
        form.put("taxInformation", section(Map.of("taxNumber", "99999999999", "vatRegistered", false)));

        Decision decision = engine.decide(application(Country.DE, form));

        assertThat(decision.identityConfidence()).isEqualTo(100);
        assertThat(decision.finalDecision()).isEqualTo(FinalDecision.DECLINE);
    }

    /** The register is matched on the country's own tax field, whatever it is called. */
    @Test
    void theRegisterIsMatchedAgainstWhicheverTaxFieldTheCountryDeclares() {
        Map<String, Object> polish = polishForm();
        polish.put("taxInformation", section(Map.of("nip", "7777777777", "vatRegistered", false)));

        assertThat(engine.decide(application(Country.PL, polish)).finalDecision())
                .isEqualTo(FinalDecision.DECLINE);
    }

    /** Separators are presentation; the register must not be defeated by a hyphen. */
    @Test
    void aFormattedTaxNumberStillMatchesTheRegister() {
        Map<String, Object> polish = polishForm();
        polish.put("taxInformation", section(Map.of("nip", "777-777-77-77", "vatRegistered", false)));

        assertThat(engine.decide(application(Country.PL, polish)).debtFlags())
                .containsExactly(Decision.DEBT_REGISTER_MATCH);
    }

    @Test
    void nameMatchingToleratesAccentsPunctuationAndOrder() {
        for (String holder : List.of("Ada Lovelace", "Lovelace, Ada", "Dr. Ada Lovelace", "ADA LOVELACE")) {
            Map<String, Object> form = germanForm();
            form.put("paymentDetails", section(Map.of(
                    "accountHolder", holder, "iban", "DE68210501700012345678")));

            assertThat(engine.decide(application(Country.DE, form)).identityConfidence())
                    .as("%s is the applicant", holder)
                    .isEqualTo(100);
        }
    }

    /** Absent evidence is not evidence of a mismatch. */
    @Test
    void anIncompleteApplicationIsNotPenalisedForWhatItDoesNotSay() {
        Decision decision = engine.decide(application(Country.DE, new LinkedHashMap<>()));

        assertThat(decision.identityConfidence()).isEqualTo(100);
        assertThat(decision.debtFlags()).isEmpty();
    }

    /**
     * The property the whole mock rests on. Anything derived from {@code Random} would make
     * every other test here meaningless, and would make a decision impossible to explain to
     * the applicant it was made about.
     */
    @Test
    void theSameApplicationAlwaysDecidesTheSameWay() {
        Map<String, Object> form = germanForm();
        form.put("paymentDetails", section(Map.of(
                "accountHolder", "Charles Babbage", "iban", "FR1420041010050500013M02606")));
        Application application = application(Country.DE, form);

        Decision first = engine.decide(application);
        for (int i = 0; i < 20; i++) {
            assertThat(engine.decide(application)).isEqualTo(first);
        }
    }

    // ------------------------------------------------------------------------------ helpers

    private Application application(Country country, Map<String, Object> formData) {
        Application application = Application.newDraft(
                UUID.randomUUID(), "hash", "applicant@example.com", country, NOW);
        formData.forEach((sectionId, values) -> application.completeSection(
                sectionId, asMap(values), stepOf(country, sectionId), NOW));
        application.submit("ONB-TEST", NOW);
        return application;
    }

    private OnboardingStep stepOf(Country country, String sectionId) {
        return flows.findByCountry(country).section(sectionId).orElseThrow().step();
    }

    private Map<String, Object> germanForm() {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("personalDetails", section(Map.of(
                "firstName", "Ada", "lastName", "Lovelace",
                "dateOfBirth", "1990-05-17", "nationality", "DE")));
        form.put("taxInformation", section(Map.of("taxNumber", "12345678901", "vatRegistered", false)));
        form.put("paymentDetails", section(Map.of(
                "accountHolder", "Ada Lovelace", "iban", "DE68210501700012345678")));
        return form;
    }

    private Map<String, Object> polishForm() {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("personalDetails", section(Map.of(
                "firstName", "Jan", "lastName", "Kowalski",
                "dateOfBirth", "1990-05-17", "nationality", "PL")));
        form.put("taxInformation", section(Map.of("nip", "8567346215", "vatRegistered", false)));
        form.put("paymentDetails", section(Map.of(
                "accountHolder", "Jan Kowalski", "iban", "PL61109010140000071219812874")));
        return form;
    }

    private Map<String, Object> section(Map<String, Object> values) {
        return new LinkedHashMap<>(values);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
