package com.zalando.onboarding.validation;

import java.util.Optional;
import org.iban4j.IbanFormatException;
import org.iban4j.IbanUtil;
import org.iban4j.InvalidCheckDigitException;
import org.iban4j.UnsupportedCountryException;
import org.springframework.stereotype.Component;

/**
 * Structural IBAN validation: format, ISO 3166-1 country, that country's expected length,
 * and the ISO 7064 mod-97 check digits, all via iban4j.
 *
 * <p>This proves the number is <em>well formed</em>. It does not prove the account exists,
 * is open, or belongs to the applicant. Bank account verification is out of scope (A12).
 *
 * <p><strong>The IBAN's country is deliberately not compared to the application's
 * country.</strong> SEPA makes a German sole trader banking with a French account entirely
 * normal, and a Polish one paid into a Dutch account is not an error to correct. Rejecting a
 * cross-border IBAN would refuse a legitimate applicant on a rule that only looks like
 * diligence.
 */
@Component
public class IbanValidator implements FieldValidator {

    @Override
    public String name() {
        return "iban";
    }

    @Override
    public Object normalise(Object value) {
        return value == null ? null : normalise(String.valueOf(value));
    }

    @Override
    public Optional<Violation> validate(String field, Object value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalised = normalise(String.valueOf(value));
        if (normalised.isEmpty()) {
            return Optional.empty();
        }
        try {
            IbanUtil.validate(normalised);
            return Optional.empty();
        } catch (InvalidCheckDigitException e) {
            return Optional.of(Violation.field(field, ViolationCode.CHECKSUM_INVALID,
                    "The IBAN check digits do not match the rest of the number"));
        } catch (IbanFormatException | UnsupportedCountryException e) {
            return Optional.of(Violation.field(field, ViolationCode.PATTERN_INVALID,
                    "Not a valid IBAN for its country"));
        }
    }

    /** Whitespace is presentational and case is not significant, so both are stripped first. */
    public static String normalise(String iban) {
        return iban == null ? null : iban.replaceAll("\\s", "").toUpperCase();
    }
}
