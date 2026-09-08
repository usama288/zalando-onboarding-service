package com.zalando.onboarding.flow;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * One field within a section, declared in YAML and implemented by the named validator beans.
 *
 * <p>This is where country variation of the first kind lives: the same field carrying a
 * different {@code pattern} or a different validator per country.
 *
 * @param label        what the applicant reads beside the control. Declared here and not
 *                     derived from {@code name} in the client, because deriving it needs to
 *                     know that NIP, REGON and KvK are acronyms and that a credit check
 *                     names SCHUFA in Germany and BKR in the Netherlands -- country
 *                     knowledge, which must never live in the frontend
 * @param validators   names of {@code FieldValidator} beans; every name is checked against
 *                     the registry at startup, so a typo cannot become a silent no-op
 * @param requiredWhen makes the field conditionally required; null means unconditional
 * @param version      only meaningful for {@link FieldType#CONSENT}: the identifier of the
 *                     consent text accepted. The text itself lives outside the application
 * @param options      a closed set of permitted values. Present makes the field a choice
 *                     rather than free text, whatever its {@code type}; empty leaves the
 *                     type to decide the control. This is what keeps a dropdown's contents
 *                     out of the frontend: a fourth country ships its own options and the
 *                     client renders them without being changed
 */
public record FieldDefinition(
        String name,
        String label,
        FieldType type,
        boolean required,
        String pattern,
        Integer maxLength,
        List<String> validators,
        RequiredWhen requiredWhen,
        String version,
        List<FieldOption> options) {

    public FieldDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("field name is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("field " + name + " has no type");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("field " + name + " has no label");
        }
        validators = validators == null ? List.of() : List.copyOf(validators);
        options = options == null ? List.of() : List.copyOf(options);
        if (type == FieldType.CONSENT && (version == null || version.isBlank())) {
            throw new IllegalArgumentException(
                    "consent field " + name + " must declare the version of the text accepted");
        }
    }

    /** True when this field is required no matter what else the section holds. Not API. */
    @JsonIgnore
    public boolean isAlwaysRequired() {
        return required && requiredWhen == null;
    }
}
