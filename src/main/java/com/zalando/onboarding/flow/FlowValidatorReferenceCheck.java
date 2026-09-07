package com.zalando.onboarding.flow;

import com.zalando.onboarding.domain.Country;
import com.zalando.onboarding.validation.FieldValidator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Fails startup if any validator named in flow YAML has no matching bean.
 *
 * <p>A typo in configuration must not become a silent no-op at runtime: {@code adlut} would
 * simply never run, the field would accept anything, and nothing would say so. Better to
 * refuse to start.
 *
 * <p>Deliberately checked against {@link FlowDefinitionRepository} rather than inside the
 * YAML loader, so the rule survives flow definitions moving to the database.
 */
@Component
public class FlowValidatorReferenceCheck implements InitializingBean {

    private final FlowDefinitionRepository flows;
    private final List<FieldValidator> validators;

    public FlowValidatorReferenceCheck(FlowDefinitionRepository flows, List<FieldValidator> validators) {
        this.flows = flows;
        this.validators = validators;
    }

    @Override
    public void afterPropertiesSet() {
        Map<String, FieldValidator> byName = indexByName();
        Set<String> known = new TreeSet<>(byName.keySet());
        List<String> unresolved = new ArrayList<>();

        for (Country country : Country.values()) {
            FlowDefinition flow = flows.findByCountry(country);
            for (SectionDefinition section : flow.sections()) {
                for (FieldDefinition field : section.fields()) {
                    for (String validator : field.validators()) {
                        if (!known.contains(validator)) {
                            unresolved.add("  '%s' at %s / %s / %s"
                                    .formatted(validator, country, section.id(), field.name()));
                        }
                    }
                }
            }
        }

        if (!unresolved.isEmpty()) {
            throw new FlowConfigurationException("""
                    Flow configuration references %d validator name(s) with no matching FieldValidator bean:
                    %s
                    Known validators: %s"""
                    .formatted(unresolved.size(), String.join("\n", unresolved), known.isEmpty()
                            ? "(none registered)"
                            : String.join(", ", known)),
                    "Either correct the name in src/main/resources/flows/*.yml, or register a "
                            + "FieldValidator bean whose name() returns it. An unresolved name is "
                            + "refused rather than skipped, because a skipped rule accepts "
                            + "anything and says nothing.");
        }
    }

    /** Two beans claiming one name is as broken as a name claiming no bean. */
    private Map<String, FieldValidator> indexByName() {
        Map<String, FieldValidator> byName = new HashMap<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (FieldValidator validator : validators) {
            FieldValidator previous = byName.put(validator.name(), validator);
            if (previous != null) {
                duplicates.add("'%s' claimed by both %s and %s".formatted(
                        validator.name(),
                        previous.getClass().getName(),
                        validator.getClass().getName()));
            }
        }
        if (!duplicates.isEmpty()) {
            throw new FlowConfigurationException(
                    "Duplicate FieldValidator names:\n  " + String.join("\n  ", duplicates));
        }
        return byName;
    }
}
