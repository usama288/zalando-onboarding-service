package com.zalando.onboarding.flow;

import com.zalando.onboarding.domain.Country;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/** Says what configuration the running instance actually holds. Identifiers only (A13). */
@Component
public class FlowDefinitionsLoadedLogger implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(FlowDefinitionsLoadedLogger.class);

    private final FlowDefinitionRepository flows;

    public FlowDefinitionsLoadedLogger(FlowDefinitionRepository flows) {
        this.flows = flows;
    }

    @Override
    public void afterPropertiesSet() {
        for (Country country : Country.values()) {
            FlowDefinition flow = flows.findByCountry(country);
            log.info("Loaded flow definition {} (schemaVersion={}) with {} sections: {}",
                    flow.country(), flow.schemaVersion(), flow.sections().size(), flow.steps());
        }
    }
}
