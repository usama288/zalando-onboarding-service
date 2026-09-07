package com.zalando.onboarding.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The two format-only rules: KvK and the German VAT ID. */
class FormatValidatorsTest {

    @Nested
    class Kvk {

        private final KvkValidator validator = new KvkValidator();

        @Test
        void acceptsExactlyEightDigits() {
            assertThat(validator.validate("kvkNumber", "12345678")).isEmpty();
            assertThat(validator.validate("kvkNumber", "00000001")).isEmpty();
        }

        @Test
        void acceptsSeparatorsAndNormalisesThemAway() {
            assertThat(validator.validate("kvkNumber", "1234 5678")).isEmpty();
            assertThat(validator.normalise("1234-5678")).isEqualTo("12345678");
        }

        @ParameterizedTest
        @ValueSource(strings = {"1234567", "123456789", "1234567A", "123456789012"})
        void rejectsAnythingElse(String value) {
            assertThat(validator.validate("kvkNumber", value))
                    .get()
                    .extracting(Violation::code)
                    .isEqualTo(ViolationCode.PATTERN_INVALID);
        }

        /**
         * KvK has no publicly specified check digit -- python-stdnum ships no KvK module and
         * no official algorithm was found -- so this is a format rule and nothing more. An
         * invented checksum would reject valid numbers, which is worse than accepting
         * well-formed invalid ones.
         */
        @Test
        void doesNotPretendToVerifyAChecksum() {
            assertThat(validator.validate("kvkNumber", "99999999"))
                    .as("well formed, certainly not a real company, and accepted")
                    .isEmpty();
        }
    }

    @Nested
    class VatIdDe {

        private final VatIdDeValidator validator = new VatIdDeValidator();

        @Test
        void acceptsDeFollowedByNineDigits() {
            // 136695976 is python-stdnum's published valid USt-IdNr example.
            assertThat(validator.validate("vatId", "DE136695976")).isEmpty();
        }

        @Test
        void normalisesCaseAndSpacing() {
            assertThat(validator.validate("vatId", "de 136 695 976")).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"DE12345678", "DE1234567890", "FR136695976", "136695976", "DEABCDEFGHI"})
        void rejectsAnythingElse(String value) {
            assertThat(validator.validate("vatId", value))
                    .get()
                    .extracting(Violation::code)
                    .isEqualTo(ViolationCode.PATTERN_INVALID);
        }

        /**
         * Format only, as specified. The real number also carries an ISO 7064 Mod 11,10 check
         * digit, so this accepts numbers the German tax office would not.
         */
        @Test
        void doesNotYetVerifyTheIso7064CheckDigit() {
            assertThat(validator.validate("vatId", "DE136695978"))
                    .as("published invalid check digit, accepted by a format-only rule")
                    .isEmpty();
        }
    }
}
