package com.zalando.onboarding.validation;

import com.zalando.onboarding.domain.Country;
import com.zalando.onboarding.flow.FieldDefinition;
import com.zalando.onboarding.flow.FlowConfigurationException;
import com.zalando.onboarding.flow.FlowDefinition;
import com.zalando.onboarding.flow.FlowDefinitionRepository;
import com.zalando.onboarding.flow.SectionDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 * YAML loader, so the rule survives flow definitions moving to the database. It lives in
 * this package so the dependency runs validation -> flow only, never back.
 */
@Component
public class FlowValidatorReferenceCheck implements InitializingBean {

    private final FlowDefinitionRepository flows;
    private final ValidatorRegistry registry;

    public FlowValidatorReferenceCheck(FlowDefinitionRepository flows, ValidatorRegistry registry) {
        this.flows = flows;
        this.registry = registry;
    }

    @Override
    public void afterPropertiesSet() {
        Set<String> known = registry.names();
        List<String> unresolved = new ArrayList<>();

        for (Country country : Country.values()) {
            FlowDefinition flow = flows.findByCountry(country);
            for (SectionDefinition section : flow.sections()) {
                for (FieldDefinition field : section.fields()) {
                    for (String validator : field.validators()) {
                        if (!registry.contains(validator)) {
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

}
