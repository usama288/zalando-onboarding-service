package com.zalando.onboarding;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ZalandoOnboardingServiceApplicationTests {

    @Test
    void contextLoads() {
        // Also asserts, via ddl-auto=validate, that the entity still matches V1__init.sql.
    }
}
