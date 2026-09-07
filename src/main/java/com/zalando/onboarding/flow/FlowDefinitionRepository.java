package com.zalando.onboarding.flow;

import com.zalando.onboarding.domain.Country;

/**
 * The seam. Flow definitions ship as YAML today; moving them into the database later must
 * not touch a single caller, so callers depend on this interface and never on the loader.
 */
public interface FlowDefinitionRepository {

    /**
     * @throws FlowConfigurationException if no definition exists for the country, which can
     *                                    only mean the configuration is broken
     */
    FlowDefinition findByCountry(Country country);
}
