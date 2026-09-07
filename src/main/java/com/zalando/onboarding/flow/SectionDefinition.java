package com.zalando.onboarding.flow;

import com.zalando.onboarding.domain.OnboardingStep;
import java.util.List;
import java.util.Optional;

/**
 * One step of the form. Country variation of the second kind lives here: a section may carry
 * an entirely different field list per country, as the business registry does.
 */
public record SectionDefinition(
        String id,
        OnboardingStep step,
        String title,
        List<FieldDefinition> fields) {

    public SectionDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("section id is required");
        }
        if (step == null) {
            throw new IllegalArgumentException("section " + id + " has no step");
        }
        fields = List.copyOf(fields == null ? List.of() : fields);
    }

    public Optional<FieldDefinition> field(String name) {
        return fields.stream().filter(field -> field.name().equals(name)).findFirst();
    }
}
