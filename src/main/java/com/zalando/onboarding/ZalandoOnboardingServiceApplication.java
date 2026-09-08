package com.zalando.onboarding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ZalandoOnboardingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZalandoOnboardingServiceApplication.class, args);
    }

}
