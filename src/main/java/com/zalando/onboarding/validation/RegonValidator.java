package com.zalando.onboarding.validation;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * REGON, the Polish statistical register number: nine digits for a national entity, or
 * fourteen when a local unit appends five more.
 *
 * <p>Algorithm and both weight vectors taken from python-stdnum's {@code stdnum/pl/regon.py}:
 * https://github.com/arthurdejong/python-stdnum/blob/master/stdnum/pl/regon.py
 *
 * <p>A fourteen-digit REGON carries <em>two</em> check digits: its own at position 14, and
 * the nine-digit entity's at position 9, which remains embedded in the longer number. Both
 * are verified. Checking only the final digit would accept 12345678612342, which the
 * reference implementation rejects and which passes the fourteen-digit checksum cleanly.
 */
@Component
public class RegonValidator implements FieldValidator {

    private static final int[] WEIGHTS_9 = {8, 9, 2, 3, 4, 5, 6, 7};
    private static final int[] WEIGHTS_14 = {2, 4, 8, 5, 0, 9, 7, 3, 6, 1, 2, 4, 8};

    @Override
    public String name() {
        return "regon";
    }

    @Override
    public Object normalise(Object value) {
        return value == null ? null : FieldValidator.stripSeparators(String.valueOf(value).trim());
    }

    @Override
    public Optional<Violation> validate(String field, Object value) {
        if (value == null) {
            return Optional.empty();
        }
        String regon = FieldValidator.stripSeparators(String.valueOf(value).trim());
        if (regon.isEmpty()) {
            return Optional.empty();
        }
        if (!regon.matches("\\d{9}|\\d{14}")) {
            return Optional.of(Violation.field(field, ViolationCode.PATTERN_INVALID,
                    "A REGON is 9 or 14 digits, with or without separators"));
        }

        if (checkDigitOf(regon.substring(0, regon.length() - 1)) != lastDigitOf(regon)) {
            return Optional.of(invalidChecksum(field));
        }
        if (regon.length() == 14 && checkDigitOf(regon.substring(0, 8)) != digitAt(regon, 8)) {
            return Optional.of(invalidChecksum(field));
        }
        return Optional.empty();
    }

    /**
     * Weighted mod 11, with a remainder of 10 mapping to 0 rather than invalidating the
     * number -- unlike NIP, where the same remainder means no such number exists.
     */
    private int checkDigitOf(String body) {
        int[] weights = body.length() == 8 ? WEIGHTS_9 : WEIGHTS_14;
        int sum = 0;
        for (int i = 0; i < weights.length && i < body.length(); i++) {
            sum += weights[i] * Character.getNumericValue(body.charAt(i));
        }
        return sum % 11 % 10;
    }

    private Violation invalidChecksum(String field) {
        return Violation.field(field, ViolationCode.CHECKSUM_INVALID,
                "The REGON check digit does not match the rest of the number");
    }

    private int digitAt(String value, int index) {
        return Character.getNumericValue(value.charAt(index));
    }

    private int lastDigitOf(String value) {
        return digitAt(value, value.length() - 1);
    }
}
