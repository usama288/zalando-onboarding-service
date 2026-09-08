package com.zalando.onboarding.validation;

import com.zalando.onboarding.domain.Application;
import com.zalando.onboarding.domain.OnboardingStep;
import com.zalando.onboarding.flow.FieldDefinition;
import com.zalando.onboarding.flow.FieldType;
import com.zalando.onboarding.flow.FlowDefinition;
import com.zalando.onboarding.flow.SectionDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Layer 2: completeness and cross-section rules, run at submit.
 *
 * <p>Not redundant with layer 1. There is no authentication anywhere in this system, so a
 * client can POST /submit directly against a half-filled application without ever having
 * walked the form. Layer 1 guarantees that whatever <em>is</em> stored is valid; only layer 2
 * can say whether enough of it is there.
 *
 * <p>It deliberately does not re-run field rules over stored data: nothing reaches form_data
 * without passing layer 1, so repeating those checks would only be able to disagree with
 * itself.
 */
@Component
public class SubmissionValidator {

    /** The consent that gates the mocked decisioning: without it, no check may be run. */
    static final String CREDIT_CHECK_CONSENT = "creditCheck";

    public List<Violation> validate(Application application, FlowDefinition flow) {
        Map<String, Object> formData = application.getFormData();
        List<Violation> violations = new ArrayList<>();

        for (SectionDefinition section : flow.sections()) {
            if (!formData.containsKey(section.id())) {
                violations.add(Violation.section(section.id(), ViolationCode.SECTION_MISSING,
                        "The " + section.id() + " section has not been completed"));
            }
        }

        creditCheckConsent(flow, formData).ifPresent(violations::add);

        return List.copyOf(violations);
    }

    /**
     * Cross-section rule: the credit-check consent must be present and accepted.
     *
     * <p>Skipped when the consent section is missing entirely -- that is already reported as
     * SECTION_MISSING, and saying it twice tells the applicant nothing new.
     */
    private Optional<Violation> creditCheckConsent(FlowDefinition flow, Map<String, Object> formData) {
        Optional<SectionDefinition> consentSection = flow.section(OnboardingStep.CONSENT);
        if (consentSection.isEmpty()) {
            return Optional.empty();
        }
        SectionDefinition consent = consentSection.get();

        boolean flowRequiresIt = consent.field(CREDIT_CHECK_CONSENT)
                .filter(field -> field.type() == FieldType.CONSENT)
                .filter(FieldDefinition::isAlwaysRequired)
                .isPresent();
        if (!flowRequiresIt || !formData.containsKey(consent.id())) {
            return Optional.empty();
        }

        Object accepted = sectionData(formData, consent.id()).get(CREDIT_CHECK_CONSENT);
        if (ConsentAcceptance.isAccepted(accepted)) {
            return Optional.empty();
        }
        return Optional.of(new Violation(CREDIT_CHECK_CONSENT, consent.id(), ViolationCode.REQUIRED,
                "The credit check consent must be accepted before an application can be submitted"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sectionData(Map<String, Object> formData, String sectionId) {
        Object section = formData.get(sectionId);
        return section instanceof Map ? (Map<String, Object>) section : Map.of();
    }
}
