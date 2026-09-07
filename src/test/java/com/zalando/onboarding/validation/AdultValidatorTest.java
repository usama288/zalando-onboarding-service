package com.zalando.onboarding.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The clock is fixed so the eighteenth-birthday boundary is a fact rather than a function of
 * when the suite runs. A leap day is chosen for "today" because 29 February is exactly where
 * naive age arithmetic breaks.
 */
class AdultValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2028, 2, 29);

    private final AdultValidator validator = new AdultValidator(
            Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));

    @Test
    void eighteenYearsAgoTodayPasses() {
        LocalDate eighteenthBirthdayIsToday = TODAY.minusYears(18);

        assertThat(validator.validate("dateOfBirth", eighteenthBirthdayIsToday.toString()))
                .as("turning 18 today makes you an adult today")
                .isEmpty();
    }

    @Test
    void oneDayLaterFails() {
        LocalDate oneDayTooYoung = TODAY.minusYears(18).plusDays(1);

        assertThat(validator.validate("dateOfBirth", oneDayTooYoung.toString()))
                .as("eighteenth birthday falls tomorrow")
                .get()
                .extracting(Violation::code)
                .isEqualTo(ViolationCode.NOT_ADULT);
    }

    @Test
    void oneDayEarlierPasses() {
        assertThat(validator.validate("dateOfBirth", TODAY.minusYears(18).minusDays(1).toString()))
                .isEmpty();
    }

    @Test
    void theBoundaryHoldsOnANonLeapYearToo() {
        LocalDate today = LocalDate.of(2027, 6, 15);
        AdultValidator onThatDay = new AdultValidator(
                Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));

        assertThat(onThatDay.validate("dateOfBirth", today.minusYears(18).toString())).isEmpty();
        assertThat(onThatDay.validate("dateOfBirth", today.minusYears(18).plusDays(1).toString()))
                .isPresent();
    }

    @Test
    void aChildIsRejected() {
        assertThat(validator.validate("dateOfBirth", TODAY.minusYears(12).toString()))
                .get()
                .extracting(Violation::code)
                .isEqualTo(ViolationCode.NOT_ADULT);
    }

    @Test
    void aFutureDateOfBirthIsRejectedAsNotAdultRatherThanAccepted() {
        assertThat(validator.validate("dateOfBirth", TODAY.plusYears(1).toString()))
                .get()
                .extracting(Violation::code)
                .isEqualTo(ViolationCode.NOT_ADULT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"29-02-2010", "2010/02/28", "yesterday", "2010-13-01", "2010-02-30"})
    void rejectsAnythingThatIsNotAnIsoDate(String value) {
        assertThat(validator.validate("dateOfBirth", value))
                .get()
                .extracting(Violation::code)
                .isEqualTo(ViolationCode.PATTERN_INVALID);
    }

    @Test
    void absenceIsNotThisValidatorsProblem() {
        assertThat(validator.validate("dateOfBirth", null)).isEmpty();
        assertThat(validator.validate("dateOfBirth", "  ")).isEmpty();
    }
}
