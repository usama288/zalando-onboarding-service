package com.zalando.onboarding.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IbanValidatorTest {

    /**
     * Published IBANs, not constructed ones.
     *
     * <ul>
     *   <li>GB82WEST12345698765432 -- the canonical ISO 13616 example, en.wikipedia
     *       "International Bank Account Number"</li>
     *   <li>DE68210501700012345678, DE07123412341234123412 -- de.wikipedia
     *       "Internationale Bankkontonummer"</li>
     *   <li>NL20INGB0001234567 -- nl.wikipedia "International Bank Account Number"</li>
     * </ul>
     */
    private static final String PUBLISHED_DE = "DE68210501700012345678";
    private static final String PUBLISHED_NL = "NL20INGB0001234567";
    private static final String PUBLISHED_GB = "GB82WEST12345698765432";

    /** Published in the same de.wikipedia article as the pre-checksum placeholder form. */
    private static final String PUBLISHED_INVALID_DE = "DE00210501700012345678";

    private final IbanValidator validator = new IbanValidator();

    @ParameterizedTest
    @ValueSource(strings = {PUBLISHED_DE, PUBLISHED_NL, PUBLISHED_GB, "DE07123412341234123412"})
    void acceptsPublishedIbans(String iban) {
        assertThat(validator.validate("iban", iban)).isEmpty();
    }

    @Test
    void rejectsAPublishedIbanWithWrongCheckDigits() {
        assertThat(validator.validate("iban", PUBLISHED_INVALID_DE))
                .get()
                .extracting(Violation::code)
                .isEqualTo(ViolationCode.CHECKSUM_INVALID);
    }

    @Test
    void normalisesLowercaseAndSpacesBeforeChecking() {
        assertThat(validator.validate("iban", "de68 2105 0170 0012 3456 78")).isEmpty();
        assertThat(validator.validate("iban", "  nl20 ingb 0001 2345 67  ")).isEmpty();
        assertThat(validator.validate("iban", "gb82 west 1234 5698 7654 32")).isEmpty();
    }

    /**
     * The rule that must NOT exist. SEPA makes a German sole trader banking with a Dutch or
     * British account entirely normal, so an IBAN whose country differs from the application
     * country is not an error. This test exists to fail if somebody later "tightens" the
     * validator by comparing the two.
     */
    @Test
    void acceptsAnIbanWhoseCountryDiffersFromTheApplicationCountry() {
        // A Polish application paying into a German account, and a German one into a Dutch:
        assertThat(validator.validate("iban", PUBLISHED_DE))
                .as("PL applicant, DE account")
                .isEmpty();
        assertThat(validator.validate("iban", PUBLISHED_NL))
                .as("DE applicant, NL account")
                .isEmpty();
        assertThat(validator.validate("iban", PUBLISHED_GB))
                .as("NL applicant, GB account -- outside the three markets entirely")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DE6821050170001234567",    // one digit short for Germany
            "DE682105017000123456789",  // one digit long
            "ZZ68210501700012345678",   // not an ISO 3166-1 country
            "notaniban",
    })
    void rejectsStructurallyWrongIbans(String iban) {
        assertThat(validator.validate("iban", iban))
                .get()
                .extracting(Violation::code)
                .isIn(ViolationCode.PATTERN_INVALID, ViolationCode.CHECKSUM_INVALID);
    }

    @Test
    void absenceIsNotThisValidatorsProblem() {
        assertThat(validator.validate("iban", null)).isEmpty();
        assertThat(validator.validate("iban", "   ")).isEmpty();
    }
}
