package com.zalando.onboarding.validation;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * German USt-IdNr: the letters DE followed by nine digits.
 *
 * <p>Format only, as specified. The real number additionally carries an ISO 7064 Mod 11,10
 * check digit (see python-stdnum {@code stdnum/de/vat.py}); adding that check would be a
 * strictly tighter rule, and is noted rather than assumed.
 */
@Component
public class VatIdDeValidator implements FieldValidator {

    @Override
    public String name() {
        return "vatIdDe";
    }

    @Override
    public Optional<Violation> validate(String field, Object value) {
        if (value == null) {
            return Optional.empty();
        }
        String vatId = String.valueOf(value).trim().replaceAll("\\s", "").toUpperCase();
        if (vatId.isEmpty()) {
            return Optional.empty();
        }
        if (!vatId.matches("DE\\d{9}")) {
            return Optional.of(Violation.field(field, ViolationCode.PATTERN_INVALID,
                    "A German VAT ID is DE followed by 9 digits"));
        }
        return Optional.empty();
    }
}
