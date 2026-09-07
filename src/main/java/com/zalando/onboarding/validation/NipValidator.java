package com.zalando.onboarding.validation;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * NIP (Numer Identyfikacji Podatkowej), the Polish tax number: ten digits under a weighted
 * mod-11 checksum.
 *
 * <p>Algorithm and weights taken from python-stdnum's {@code stdnum/pl/nip.py}, not from
 * memory: https://github.com/arthurdejong/python-stdnum/blob/master/stdnum/pl/nip.py
 */
@Component
public class NipValidator implements FieldValidator {

    private static final int[] WEIGHTS = {6, 5, 7, 2, 3, 4, 5, 6, 7};

    @Override
    public String name() {
        return "nip";
    }

    /** 856-734-62-15 is how a NIP is written; it is stored as 8567346215. */
    @Override
    public Object normalise(Object value) {
        return value == null ? null : FieldValidator.stripSeparators(String.valueOf(value).trim());
    }

    @Override
    public Optional<Violation> validate(String field, Object value) {
        if (value == null) {
            return Optional.empty();
        }
        String nip = FieldValidator.stripSeparators(String.valueOf(value).trim());
        if (nip.isEmpty()) {
            return Optional.empty();
        }
        if (!nip.matches("\\d{10}")) {
            return Optional.of(Violation.field(field, ViolationCode.PATTERN_INVALID,
                    "A NIP is 10 digits, with or without separators"));
        }

        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += WEIGHTS[i] * Character.getNumericValue(nip.charAt(i));
        }
        int expected = sum % 11;

        // A remainder of 10 cannot be written as a single check digit, so no NIP with this
        // prefix exists. Rejected rather than wrapped to 0, which would invent a valid number.
        if (expected == 10 || expected != Character.getNumericValue(nip.charAt(9))) {
            return Optional.of(Violation.field(field, ViolationCode.CHECKSUM_INVALID,
                    "The NIP check digit does not match the rest of the number"));
        }
        return Optional.empty();
    }
}
