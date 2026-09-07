package com.zalando.onboarding.support;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /** Injected instead of calling now() directly, so age boundaries are fixable in tests. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
