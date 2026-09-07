package com.zalando.onboarding.flow;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.zalando.onboarding.domain.Country;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

/**
 * Loads {@code classpath:flows/{DE,PL,NL}.yml} once, at startup, into an immutable map.
 *
 * <p>Nothing is read from disk after construction, so a definition cannot change under a
 * live draft (A21). Unknown YAML keys are rejected rather than ignored: a mistyped key is a
 * rule that silently would not apply, which is the failure this whole phase exists to
 * prevent.
 */
@Repository
public class YamlFlowDefinitionRepository implements FlowDefinitionRepository {

    private static final String LOCATION = "flows/%s.yml";

    private final Map<Country, FlowDefinition> definitions;

    public YamlFlowDefinitionRepository() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        EnumMap<Country, FlowDefinition> loaded = new EnumMap<>(Country.class);
        for (Country country : Country.values()) {
            loaded.put(country, load(mapper, country));
        }
        this.definitions = Collections.unmodifiableMap(loaded);
    }

    private FlowDefinition load(ObjectMapper mapper, Country country) {
        String location = LOCATION.formatted(country.name());
        ClassPathResource resource = new ClassPathResource(location);
        if (!resource.exists()) {
            throw new FlowConfigurationException(
                    "No flow definition on the classpath at " + location + " for country " + country
                            + ". Every country in the Country enum must have one.");
        }
        FlowDefinition definition;
        try (InputStream yaml = resource.getInputStream()) {
            definition = mapper.readValue(yaml, FlowDefinition.class);
        } catch (IOException | IllegalArgumentException e) {
            throw new FlowConfigurationException(
                    "Could not read the flow definition at " + location + ": " + e.getMessage(),
                    "Fix the YAML at src/main/resources/" + location + ". Unknown keys are "
                            + "rejected on purpose: a mistyped key is a rule that would silently "
                            + "never apply. The message above names the line and lists the keys "
                            + "this element accepts.",
                    e);
        }
        if (definition.country() != country) {
            throw new FlowConfigurationException(
                    "Flow definition at " + location + " declares country " + definition.country()
                            + " but the filename says " + country + ".");
        }
        return definition;
    }

    @Override
    public FlowDefinition findByCountry(Country country) {
        FlowDefinition definition = definitions.get(country);
        if (definition == null) {
            throw new FlowConfigurationException("No flow definition loaded for country " + country);
        }
        return definition;
    }
}
