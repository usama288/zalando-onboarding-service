package com.zalando.onboarding.validation;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * KvK number, the Dutch Chamber of Commerce registration: exactly eight digits.
 *
 * <p>Format only, and deliberately so. The KvK number has no publicly specified check digit:
 * python-stdnum ships modules for Dutch BSN, BTW, BRIN and postcode but none for KvK, and no
 * official algorithm was found. Inventing a checksum here would reject valid numbers, which
 * is worse than accepting well-formed invalid ones. Real verification needs the KvK API,
 * which is out of scope.
 */
@Component
public class KvkValidator implements FieldValidator {

    @Override
    public String name() {
        return "kvk";
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
        String kvk = FieldValidator.stripSeparators(String.valueOf(value).trim());
        if (kvk.isEmpty()) {
            return Optional.empty();
        }
        if (!kvk.matches("\\d{8}")) {
            return Optional.of(Violation.field(field, ViolationCode.PATTERN_INVALID,
                    "A KvK number is 8 digits, with or without separators"));
        }
        return Optional.empty();
    }
}
