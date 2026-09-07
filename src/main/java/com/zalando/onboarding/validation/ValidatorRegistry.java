package com.zalando.onboarding.validation;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Resolves {@link FieldValidator} beans by the name flow YAML references them under.
 *
 * <p>Two beans claiming one name is as broken as a name claiming no bean -- one would
 * silently shadow the other -- so that fails here, at construction, rather than producing a
 * coin-flip at runtime.
 */
@Component
public class ValidatorRegistry {

    private final Map<String, FieldValidator> byName;

    public ValidatorRegistry(List<FieldValidator> validators) {
        Map<String, FieldValidator> index = new HashMap<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (FieldValidator validator : validators) {
            FieldValidator previous = index.put(validator.name(), validator);
            if (previous != null) {
                duplicates.add("'%s' claimed by both %s and %s".formatted(
                        validator.name(), previous.getClass().getName(), validator.getClass().getName()));
            }
        }
        if (!duplicates.isEmpty()) {
            throw new IllegalStateException(
                    "Duplicate FieldValidator names:\n  " + String.join("\n  ", duplicates));
        }
        this.byName = Map.copyOf(index);
    }

    public Optional<FieldValidator> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * @throws IllegalStateException if the name has no bean. Callers reach this only after
     *                               the startup check has passed, so a miss here is a bug
     */
    public FieldValidator get(String name) {
        return find(name).orElseThrow(() -> new IllegalStateException(
                "No FieldValidator named '" + name + "'. Known: " + names()));
    }

    public boolean contains(String name) {
        return byName.containsKey(name);
    }

    /** Sorted, so error messages listing them are stable. */
    public Set<String> names() {
        return new TreeSet<>(byName.keySet());
    }
}
