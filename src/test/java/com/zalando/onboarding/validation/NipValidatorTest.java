package com.zalando.onboarding.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NipValidatorTest {

    /**
     * Published valid NIP, taken from python-stdnum's pl/nip.py doctest -- an external
     * reference implementation, not a number this codebase produced. A suite built only from
     * self-generated vectors would pass identically with the wrong weight vector.
     *
     * @see <a href="https://github.com/arthurdejong/python-stdnum/blob/master/stdnum/pl/nip.py">stdnum/pl/nip.py</a>
     */
    private static final String PUBLISHED_VALID = "8567346215";

    /** From the same doctest, documented there as "invalid check digits". */
    private static final String PUBLISHED_INVALID = "8567346216";

    private final NipValidator validator = new NipValidator();

    @Test
    void acceptsThePublishedValidNip() {
        assertThat(validator.validate("nip", PUBLISHED_VALID)).isEmpty();
    }

    @Test
    void rejectsThePublishedInvalidNip() {
        assertThat(validator.validate("nip", PUBLISHED_INVALID))
                .get()
                .extracting(Violation::code)
                .isEqualTo(ViolationCode.CHECKSUM_INVALID);
    }

    @Test
    void everySingleDigitChangeToAValidNipIsCaught() {
        // The weights are only worth having if they detect the errors people actually make.
        int caught = 0;
        int mutations = 0;
        for (int position = 0; position < 10; position++) {
            for (char digit = '0'; digit <= '9'; digit++) {
                if (PUBLISHED_VALID.charAt(position) == digit) {
                    continue;
                }
                char[] mutated = PUBLISHED_VALID.toCharArray();
                mutated[position] = digit;
                mutations++;
                if (validator.validate("nip", new String(mutated)).isPresent()) {
                    caught++;
                }
            }
        }
        assertThat(mutations).isEqualTo(90);
        assertThat(caught).as("mod-11 catches every single-digit error").isEqualTo(90);
    }

    /**
     * The boundary the algorithm cannot express: a prefix whose weighted sum leaves 10.
     * No single digit can hold that value, so no valid NIP has this prefix and it must be
     * rejected rather than wrapped to 0 -- wrapping would invent a valid number.
     */
    @Test
    void rejectsANipWhoseCheckDigitComputesToTen() {
        String prefixWithRemainderTen = findPrefixWhoseCheckDigitIsTen();

        for (char digit = '0'; digit <= '9'; digit++) {
            assertThat(validator.validate("nip", prefixWithRemainderTen + digit))
                    .as("no check digit can rescue prefix %s", prefixWithRemainderTen)
                    .get()
                    .extracting(Violation::code)
                    .isEqualTo(ViolationCode.CHECKSUM_INVALID);
        }
    }

    @Test
    void acceptsTheFormattedFormPolesActuallyWrite() {
        assertThat(validator.validate("nip", "856-734-62-15")).isEmpty();
        assertThat(validator.normalise("856-734-62-15")).isEqualTo(PUBLISHED_VALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"856734621", "85673462155", "85673462AB", "  "})
    void rejectsAnythingThatIsNotTenBareDigits(String value) {
        if (value.isBlank()) {
            assertThat(validator.validate("nip", value)).isEmpty();
            return;
        }
        assertThat(validator.validate("nip", value))
                .get()
                .extracting(Violation::code)
                .isEqualTo(ViolationCode.PATTERN_INVALID);
    }

    @Test
    void absenceIsNotThisValidatorsProblem() {
        assertThat(validator.validate("nip", null)).isEmpty();
    }

    private String findPrefixWhoseCheckDigitIsTen() {
        int[] weights = {6, 5, 7, 2, 3, 4, 5, 6, 7};
        for (int candidate = 100000000; candidate < 100001000; candidate++) {
            String prefix = String.valueOf(candidate);
            int sum = 0;
            for (int i = 0; i < weights.length; i++) {
                sum += weights[i] * Character.getNumericValue(prefix.charAt(i));
            }
            if (sum % 11 == 10) {
                return prefix;
            }
        }
        throw new AssertionError("no 9-digit prefix with remainder 10 found in the search range");
    }
}
