package com.zalando.onboarding.decision;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param debtRegisterTaxIds demo data standing in for a credit bureau. Configured rather than
 *                           compiled in so a demo can be arranged without a rebuild, and so
 *                           it is obvious this is fixture data and not a real register
 * @param referBelowConfidence the identity confidence at or above which an application is
 *                             approved. Below it, a human is wanted
 */
@ConfigurationProperties(prefix = "onboarding.decision")
public record DecisionProperties(List<String> debtRegisterTaxIds, int referBelowConfidence) {

    public DecisionProperties {
        debtRegisterTaxIds = List.copyOf(debtRegisterTaxIds == null ? List.of() : debtRegisterTaxIds);
        if (referBelowConfidence < 0 || referBelowConfidence > 100) {
            throw new IllegalArgumentException(
                    "onboarding.decision.refer-below-confidence must be between 0 and 100");
        }
    }
}
