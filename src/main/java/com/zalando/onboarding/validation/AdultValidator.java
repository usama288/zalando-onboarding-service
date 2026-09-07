package com.zalando.onboarding.validation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A date of birth that parses as an ISO date and falls at least eighteen years before today.
 *
 * <p>The boundary is inclusive: somebody whose eighteenth birthday is today is an adult. A
 * date one day later is not. Time comes from an injected {@link Clock} so that boundary is
 * testable rather than dependent on when the suite happens to run.
 */
@Component
public class AdultValidator implements FieldValidator {

    private static final int MINIMUM_AGE = 18;

    private final Clock clock;

    public AdultValidator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "adult";
    }

    @Override
    public Optional<Violation> validate(String field, Object value) {
        if (value == null) {
            return Optional.empty();
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return Optional.empty();
        }

        LocalDate dateOfBirth;
        try {
            dateOfBirth = LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            return Optional.of(Violation.field(field, ViolationCode.PATTERN_INVALID,
                    "Must be a date in YYYY-MM-DD form"));
        }

        LocalDate today = LocalDate.now(clock);
        // Turning 18 today passes; isAfter is false when the birthday lands exactly on today.
        if (dateOfBirth.plusYears(MINIMUM_AGE).isAfter(today)) {
            return Optional.of(Violation.field(field, ViolationCode.NOT_ADULT,
                    "You must be at least " + MINIMUM_AGE + " years old"));
        }
        return Optional.empty();
    }
}
