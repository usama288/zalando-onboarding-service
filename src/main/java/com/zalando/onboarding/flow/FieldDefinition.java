package com.zalando.onboarding.flow;

import java.util.List;

/**
 * One field within a section, declared in YAML and implemented by the named validator beans.
 *
 * <p>This is where country variation of the first kind lives: the same field carrying a
 * different {@code pattern} or a different validator per country.
 *
 * @param validators   names of {@code FieldValidator} beans; every name is checked against
 *                     the registry at startup, so a typo cannot become a silent no-op
 * @param requiredWhen makes the field conditionally required; null means unconditional
 * @param version      only meaningful for {@link FieldType#CONSENT}: the identifier of the
 *                     consent text accepted. The text itself lives outside the application
 */
public record FieldDefinition(
        String name,
        FieldType type,
        boolean required,
        String pattern,
        Integer maxLength,
        List<String> validators,
        RequiredWhen requiredWhen,
        String version) {

    public FieldDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("field name is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("field " + name + " has no type");
        }
        validators = validators == null ? List.of() : List.copyOf(validators);
        if (type == FieldType.CONSENT && (version == null || version.isBlank())) {
            throw new IllegalArgumentException(
                    "consent field " + name + " must declare the version of the text accepted");
        }
    }

    /** True when this field is required no matter what else the section holds. */
    public boolean isAlwaysRequired() {
        return required && requiredWhen == null;
    }
}
