package com.zalando.onboarding.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ValidatorRegistryTest {

    private record NamedValidator(String name) implements FieldValidator {
        @Override
        public Optional<Violation> validate(String field, Object value) {
            return Optional.empty();
        }
    }

    @Test
    void resolvesByTheNameYamlReferences() {
        FieldValidator iban = new NamedValidator("iban");
        ValidatorRegistry registry = new ValidatorRegistry(List.of(iban, new NamedValidator("nip")));

        assertThat(registry.get("iban")).isSameAs(iban);
        assertThat(registry.contains("nip")).isTrue();
        assertThat(registry.contains("nope")).isFalse();
        assertThat(registry.names()).containsExactly("iban", "nip");
    }

    @Test
    void failsWhenTwoBeansClaimTheSameName() {
        assertThatThrownBy(() -> new ValidatorRegistry(
                List.of(new NamedValidator("adult"), new NamedValidator("adult"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate FieldValidator names")
                .hasMessageContaining("'adult'");
    }

    @Test
    void anUnknownNameIsABugNotAUserError() {
        ValidatorRegistry registry = new ValidatorRegistry(List.of(new NamedValidator("iban")));

        assertThat(registry.find("nope")).isEmpty();
        assertThatThrownBy(() -> registry.get("nope"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No FieldValidator named 'nope'");
    }
}
