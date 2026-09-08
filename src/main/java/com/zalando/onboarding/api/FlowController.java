package com.zalando.onboarding.api;

import com.zalando.onboarding.domain.ApplicationService;
import com.zalando.onboarding.domain.Country;
import com.zalando.onboarding.domain.ValidationFailedException;
import com.zalando.onboarding.flow.FlowDefinition;
import com.zalando.onboarding.flow.FlowDefinitionRepository;
import com.zalando.onboarding.validation.Violation;
import com.zalando.onboarding.validation.ViolationCode;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The form, before there is an application to put in it. A client asks for a country's flow
 * to render step 0 and to know what it is about to collect.
 *
 * <p>The country is taken as a string and parsed here rather than bound straight to the enum,
 * so {@code /api/flows/de} works and an unknown country gets the same problem body as every
 * other refusal instead of a binding failure.
 */
@RestController
@RequestMapping("/api/flows")
public class FlowController {

    private final FlowDefinitionRepository flows;

    public FlowController(FlowDefinitionRepository flows) {
        this.flows = flows;
    }

    /**
     * Every market this service onboards in, with its whole form.
     *
     * <p>The landing page builds its country picker from this rather than from a list of its
     * own, which is what makes "a fourth country needs no frontend change" true of the very
     * first screen and not only of the fields inside it.
     */
    @GetMapping
    public List<FlowDefinition> all() {
        return Arrays.stream(Country.values()).map(flows::findByCountry).toList();
    }

    @GetMapping("/{country}")
    public FlowDefinition get(@PathVariable String country) {
        Country parsed = ApplicationService.countryOf(country)
                .orElseThrow(() -> ValidationFailedException.of("Unknown country",
                        Violation.field("country", ViolationCode.COUNTRY_UNKNOWN,
                                "Onboarding is available in DE, PL and NL")));
        return flows.findByCountry(parsed);
    }
}
