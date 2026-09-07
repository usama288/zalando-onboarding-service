package com.zalando.onboarding.flow;

import com.zalando.onboarding.domain.Country;
import com.zalando.onboarding.domain.OnboardingStep;
import java.util.List;
import java.util.Optional;

/**
 * The whole form for one country: which sections exist, in what order, carrying which
 * fields under which rules. Declared in YAML, never in {@code if (country == DE)} branches.
 */
public record FlowDefinition(Country country, int schemaVersion, List<SectionDefinition> sections) {

    public FlowDefinition {
        if (country == null) {
            throw new IllegalArgumentException("flow definition has no country");
        }
        sections = List.copyOf(sections == null ? List.of() : sections);
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("flow definition for " + country + " has no sections");
        }
    }

    public Optional<SectionDefinition> section(OnboardingStep step) {
        return sections.stream().filter(section -> section.step() == step).findFirst();
    }

    public Optional<SectionDefinition> section(String id) {
        return sections.stream().filter(section -> section.id().equals(id)).findFirst();
    }

    /** The section order for this country, which is what gating and resume both walk. */
    public List<OnboardingStep> steps() {
        return sections.stream().map(SectionDefinition::step).toList();
    }

    /**
     * Where to send an applicant who has completed {@code lastCompletedStep}.
     *
     * <p>Walks this country's own section order rather than the enum's declaration order,
     * so a country whose flow omits or reorders a section resumes correctly.
     *
     * @param lastCompletedStep the furthest section actually completed, or null on a fresh
     *                          draft. Never a viewing position
     * @return the next uncompleted section, or REVIEW once the last one is done
     */
    public ResumeStep resumeStepAfter(OnboardingStep lastCompletedStep) {
        List<OnboardingStep> order = steps();
        if (lastCompletedStep == null) {
            return ResumeStep.of(order.getFirst());
        }
        int completed = order.indexOf(lastCompletedStep);
        if (completed < 0) {
            throw new IllegalStateException("Step " + lastCompletedStep + " is not part of the "
                    + country + " flow, so there is no next step after it");
        }
        boolean wasLast = completed == order.size() - 1;
        return wasLast ? ResumeStep.REVIEW : ResumeStep.of(order.get(completed + 1));
    }
}
