package com.zalando.onboarding.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.zalando.onboarding.domain.Country;
import com.zalando.onboarding.domain.OnboardingStep;
import com.zalando.onboarding.flow.FlowDefinitionRepository;
import com.zalando.onboarding.flow.SectionDefinition;
import com.zalando.onboarding.flow.YamlFlowDefinitionRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Layer 1, run against the real shipped flow definitions. */
class SectionValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 8);

    private final FlowDefinitionRepository flows = new YamlFlowDefinitionRepository();
    private final SectionValidator validator = new SectionValidator(new ValidatorRegistry(List.of(
            new AdultValidator(Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)),
            new IbanValidator(), new KvkValidator(), new NipValidator(),
            new RegonValidator(), new VatIdDeValidator())));

    @Test
    void aCompleteValidSectionYieldsNoViolations() {
        assertThat(validate(Country.DE, OnboardingStep.PERSONAL_DETAILS, personalDetails())).isEmpty();
    }

    @Test
    void missingRequiredFieldsAreReportedPerField() {
        Map<String, Object> values = personalDetails();
        values.remove("firstName");
        values.remove("nationality");

        assertThat(validate(Country.DE, OnboardingStep.PERSONAL_DETAILS, values))
                .extracting(Violation::field, Violation::code, Violation::section)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("firstName", ViolationCode.REQUIRED, "personalDetails"),
                        org.assertj.core.groups.Tuple.tuple("nationality", ViolationCode.REQUIRED, "personalDetails"));
    }

    @Test
    void blankIsTreatedAsAbsentForRequiredness() {
        Map<String, Object> values = personalDetails();
        values.put("firstName", "   ");

        assertThat(validate(Country.DE, OnboardingStep.PERSONAL_DETAILS, values))
                .singleElement()
                .extracting(Violation::field, Violation::code)
                .containsExactly("firstName", ViolationCode.REQUIRED);
    }

    @Test
    void unknownFieldsAreRejectedRatherThanStored() {
        Map<String, Object> values = personalDetails();
        values.put("middleName", "Quentin");
        values.put("salary", 90000);

        assertThat(validate(Country.DE, OnboardingStep.PERSONAL_DETAILS, values))
                .extracting(Violation::field, Violation::code)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("middleName", ViolationCode.FIELD_UNKNOWN),
                        org.assertj.core.groups.Tuple.tuple("salary", ViolationCode.FIELD_UNKNOWN));
    }

    @Test
    void emailIsAnUnknownFieldInEverySectionBecauseItLivesOnTheRow() {
        Map<String, Object> values = personalDetails();
        values.put("email", "applicant@example.com");

        assertThat(validate(Country.DE, OnboardingStep.PERSONAL_DETAILS, values))
                .singleElement()
                .extracting(Violation::field, Violation::code)
                .containsExactly("email", ViolationCode.FIELD_UNKNOWN);
    }

    @Test
    void postalCodePatternIsEnforcedPerCountry() {
        assertThat(validate(Country.DE, OnboardingStep.ADDRESS, address("10115"))).isEmpty();
        assertThat(validate(Country.PL, OnboardingStep.ADDRESS, address("00-950"))).isEmpty();
        assertThat(validate(Country.NL, OnboardingStep.ADDRESS, address("1012 AB"))).isEmpty();

        // Each country's own code rejected by the others.
        assertThat(validate(Country.DE, OnboardingStep.ADDRESS, address("00-950")))
                .singleElement().extracting(Violation::code).isEqualTo(ViolationCode.PATTERN_INVALID);
        assertThat(validate(Country.PL, OnboardingStep.ADDRESS, address("10115")))
                .singleElement().extracting(Violation::code).isEqualTo(ViolationCode.PATTERN_INVALID);
        assertThat(validate(Country.NL, OnboardingStep.ADDRESS, address("10115")))
                .singleElement().extracting(Violation::code).isEqualTo(ViolationCode.PATTERN_INVALID);
    }

    @Test
    void maxLengthIsEnforced() {
        Map<String, Object> values = personalDetails();
        values.put("firstName", "a".repeat(101));

        assertThat(validate(Country.DE, OnboardingStep.PERSONAL_DETAILS, values))
                .singleElement()
                .extracting(Violation::field, Violation::code)
                .containsExactly("firstName", ViolationCode.TOO_LONG);
    }

    @Test
    void namedValidatorsRunAndReportTheirOwnCode() {
        Map<String, Object> values = personalDetails();
        values.put("dateOfBirth", TODAY.minusYears(10).toString());

        assertThat(validate(Country.DE, OnboardingStep.PERSONAL_DETAILS, values))
                .singleElement()
                .extracting(Violation::field, Violation::code, Violation::section)
                .containsExactly("dateOfBirth", ViolationCode.NOT_ADULT, "personalDetails");
    }

    @Test
    void conditionalFieldIsRequiredOnlyWhenItsControllerSaysSo() {
        Map<String, Object> registered = new LinkedHashMap<>(Map.of(
                "taxNumber", "12345678901", "vatRegistered", true));

        assertThat(validate(Country.DE, OnboardingStep.TAX_INFORMATION, registered))
                .as("vatRegistered=true demands a vatId")
                .singleElement()
                .extracting(Violation::field, Violation::code)
                .containsExactly("vatId", ViolationCode.REQUIRED);

        Map<String, Object> notRegistered = new LinkedHashMap<>(Map.of(
                "taxNumber", "12345678901", "vatRegistered", false));

        assertThat(validate(Country.DE, OnboardingStep.TAX_INFORMATION, notRegistered))
                .as("vatRegistered=false leaves vatId optional")
                .isEmpty();
    }

    @Test
    void oneFieldYieldsAtMostOneViolation() {
        Map<String, Object> values = personalDetails();
        // Too long AND not a two-letter code: the applicant should be told one thing.
        values.put("nationality", "GERMANY");

        assertThat(validate(Country.DE, OnboardingStep.PERSONAL_DETAILS, values))
                .singleElement()
                .extracting(Violation::field, Violation::code)
                .containsExactly("nationality", ViolationCode.TOO_LONG);
    }

    @Test
    void everyViolationCarriesTheSectionItWasFoundIn() {
        Map<String, Object> values = new HashMap<>();

        assertThat(validate(Country.PL, OnboardingStep.BUSINESS_REGISTRY, values))
                .isNotEmpty()
                .allSatisfy(violation -> assertThat(violation.section()).isEqualTo("businessRegistry"));
    }

    private List<Violation> validate(Country country, OnboardingStep step, Map<String, Object> values) {
        return result(country, step, values).violations();
    }

    private SectionValidation result(Country country, OnboardingStep step, Map<String, Object> values) {
        SectionDefinition section = flows.findByCountry(country).section(step).orElseThrow();
        return validator.validate(section, values);
    }

    /**
     * 856-734-62-15 is how a Pole writes a NIP. Rejecting it would cost applicants at the
     * exact step this MVP exists to measure, so both forms pass and both persist identically.
     */
    @Test
    void aFormattedNipAndABareNipBothPassAndPersistTheSame() {
        SectionValidation formatted = result(Country.PL, OnboardingStep.TAX_INFORMATION,
                polishTax("856-734-62-15"));
        SectionValidation bare = result(Country.PL, OnboardingStep.TAX_INFORMATION,
                polishTax("8567346215"));

        assertThat(formatted.violations()).isEmpty();
        assertThat(bare.violations()).isEmpty();
        assertThat(formatted.values().get("nip")).isEqualTo("8567346215");
        assertThat(formatted.values()).isEqualTo(bare.values());
    }

    @Test
    void regonAndKvkAndIbanAlsoPersistNormalised() {
        SectionValidation regon = result(Country.PL, OnboardingStep.BUSINESS_REGISTRY, Map.of(
                "regon", "192-598-184",
                "ceidgRegistrationDate", "2020-01-01",
                "pkdCode", "62.01.Z"));
        assertThat(regon.violations()).isEmpty();
        assertThat(regon.values().get("regon")).isEqualTo("192598184");

        SectionValidation kvk = result(Country.NL, OnboardingStep.BUSINESS_REGISTRY, Map.of(
                "kvkNumber", "1234-5678", "tradeName", "Ada BV", "sbiCode", "6201"));
        assertThat(kvk.violations()).isEmpty();
        assertThat(kvk.values().get("kvkNumber")).isEqualTo("12345678");

        SectionValidation payment = result(Country.DE, OnboardingStep.PAYMENT_DETAILS, Map.of(
                "accountHolder", "Ada Lovelace", "iban", "de68 2105 0170 0012 3456 78"));
        assertThat(payment.violations()).isEmpty();
        assertThat(payment.values().get("iban")).isEqualTo("DE68210501700012345678");
    }

    @Test
    void fieldsWithNoValidatorArePersistedExactlyAsTheyArrived() {
        SectionValidation result = result(Country.DE, OnboardingStep.PERSONAL_DETAILS, personalDetails());

        assertThat(result.values().get("firstName")).isEqualTo("Ada");
        assertThat(result.values()).containsOnlyKeys("firstName", "lastName", "dateOfBirth", "nationality");
    }

    private Map<String, Object> polishTax(String nip) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("nip", nip);
        values.put("vatRegistered", false);
        return values;
    }

    private Map<String, Object> personalDetails() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("firstName", "Ada");
        values.put("lastName", "Lovelace");
        values.put("dateOfBirth", TODAY.minusYears(30).toString());
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
}
