package com.zalando.onboarding.validation;

import com.zalando.onboarding.flow.FieldDefinition;
import com.zalando.onboarding.flow.FieldType;
import com.zalando.onboarding.flow.SectionDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Layer 1: field validation for one section, run before anything is persisted.
 *
 * <p>Checks run in a fixed order per field -- required, requiredWhen, maxLength, pattern,
 * then the named validators -- and stop at the first failure for that field. One field
 * yields at most one violation, so the applicant gets a clear reason rather than a cascade
 * saying the same value is both too long and malformed.
 *
 * <p>Unknown fields are refused with FIELD_UNKNOWN, not dropped. Silently discarding them
 * would let a client believe it had saved something the server never stored, and silently
 * keeping them would let unvalidated data into form_data through a field nobody declared.
 */
@Component
public class SectionValidator {

    private final ValidatorRegistry registry;

    public SectionValidator(ValidatorRegistry registry) {
        this.registry = registry;
    }

    public SectionValidation validate(SectionDefinition section, Map<String, Object> values) {
        Map<String, Object> submitted = values == null ? Map.of() : values;
        List<Violation> violations = new ArrayList<>();
        Map<String, Object> normalised = new LinkedHashMap<>();

        for (FieldDefinition field : section.fields()) {
            // Normalise before checking. maxLength and pattern constrain what gets stored,
            // and what gets stored is the normalised form; measuring the raw value would
            // reject 856-734-62-15 for being longer than the 10-digit NIP it normalises to.
            Object value = normalise(field, submitted.get(field.name()));

            Optional<Violation> violation = validateField(section, field, submitted, value);
            if (violation.isPresent()) {
                violations.add(violation.get());
            } else if (value != null) {
                normalised.put(field.name(), value);
            }
        }
        violations.addAll(unknownFields(section, submitted));

        return new SectionValidation(violations, normalised);
    }

    /**
     * The form to persist. Only the named validators normalise; a field with no validator is
     * stored exactly as it arrived, so this cannot quietly rewrite a value no rule claimed.
     */
    private Object normalise(FieldDefinition field, Object value) {
        Object normalised = value;
        for (String validatorName : field.validators()) {
            if (normalised == null) {
                return null;
            }
            normalised = registry.get(validatorName).normalise(normalised);
        }
        return normalised;
    }

    private Optional<Violation> validateField(SectionDefinition section, FieldDefinition field,
                                              Map<String, Object> submitted, Object value) {
        if (field.type() == FieldType.CONSENT) {
            return consent(section, field, submitted, value);
        }

        boolean absent = value == null || String.valueOf(value).trim().isEmpty();

        if (isRequired(field, submitted)) {
            if (absent) {
                return Optional.of(violation(section, field.name(), ViolationCode.REQUIRED,
                        "This field is required"));
            }
        } else if (absent) {
            // Optional and not supplied: nothing downstream has anything to check.
            return Optional.empty();
        }

        String text = String.valueOf(value).trim();

        if (field.maxLength() != null && text.length() > field.maxLength()) {
            return Optional.of(violation(section, field.name(), ViolationCode.TOO_LONG,
                    "Must be at most " + field.maxLength() + " characters"));
        }
        if (field.pattern() != null && !text.matches(field.pattern())) {
            return Optional.of(violation(section, field.name(), ViolationCode.PATTERN_INVALID,
                    "Not in the expected format"));
        }
        for (String validatorName : field.validators()) {
            Optional<Violation> violation = registry.get(validatorName).validate(field.name(), value);
            if (violation.isPresent()) {
                return violation.map(v -> v.inSection(section.id()));
            }
        }
        return Optional.empty();
    }

    /**
     * A consent is answered by being accepted, and by nothing else.
     *
     * <p>Presence is not the test. {@code false} is a well-formed value that is not blank, so
     * the ordinary required check counted it as answered and stored a refusal -- and layer 2
     * only ever re-examines {@code creditCheck}, so every other consent could be submitted
     * refused. Absent and refused are now the same violation, because to the applicant they
     * are the same act: they did not agree.
     *
     * <p>Nothing else is checked here. A consent has no format, no length and no checksum;
     * whether the box was ticked is the whole question.
     */
    private Optional<Violation> consent(SectionDefinition section, FieldDefinition field,
                                        Map<String, Object> submitted, Object value) {
        if (isRequired(field, submitted) && !ConsentAcceptance.isAccepted(value)) {
            return Optional.of(violation(section, field.name(), ViolationCode.REQUIRED,
                    "This consent must be accepted"));
        }
        return Optional.empty();
    }

    /**
     * Whether this field must be supplied, given what else the section carries. Conditional
     * rules delegate to {@link com.zalando.onboarding.flow.RequiredWhen}, so layer 1 and
     * layer 2 cannot disagree about completeness.
     */
    private boolean isRequired(FieldDefinition field, Map<String, Object> submitted) {
        if (field.requiredWhen() != null) {
            return field.requiredWhen().matches(submitted.get(field.requiredWhen().field()));
        }
        return field.required();
    }

    private List<Violation> unknownFields(SectionDefinition section, Map<String, Object> submitted) {
        Set<String> declared = new LinkedHashSet<>();
        section.fields().forEach(field -> declared.add(field.name()));

        return submitted.keySet().stream()
                .filter(name -> !declared.contains(name))
                .sorted()
                .map(name -> violation(section, name, ViolationCode.FIELD_UNKNOWN,
                        "The " + section.id() + " section has no field called '" + name + "'"))
                .toList();
    }

    private Violation violation(SectionDefinition section, String field, String code, String message) {
        return new Violation(field, section.id(), code, message);
    }
}
