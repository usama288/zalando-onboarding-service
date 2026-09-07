package com.zalando.onboarding.validation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the validator names the flow definitions reference, and nothing else.
 *
 * <p>These carry no validation logic on purpose: P2 builds the configuration engine, P3
 * builds the rules. They exist because the startup check in P2 must be real -- a check that
 * cannot pass proves nothing, and a check that is switched off until P3 would let a typo sit
 * undetected for exactly as long as it takes to forget about it.
 *
 * <p>P3 deletes this class, replacing each name with a bean that actually validates.
 */
@Configuration(proxyBeanMethods = false)
public class PlaceholderFieldValidators {

    @Bean
    FieldValidator adultValidator() {
        return () -> "adult";
    }

    @Bean
    FieldValidator ibanValidator() {
        return () -> "iban";
    }

    @Bean
    FieldValidator nipValidator() {
        return () -> "nip";
    }

    @Bean
    FieldValidator regonValidator() {
        return () -> "regon";
    }

    @Bean
    FieldValidator kvkValidator() {
        return () -> "kvk";
    }

    @Bean
    FieldValidator vatIdDeValidator() {
        return () -> "vatIdDe";
    }
}
