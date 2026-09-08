package com.zalando.onboarding.decision;

import com.zalando.onboarding.domain.Application;
import com.zalando.onboarding.domain.Country;
import com.zalando.onboarding.domain.OnboardingStep;
import com.zalando.onboarding.flow.FieldDefinition;
import com.zalando.onboarding.flow.FieldType;
import com.zalando.onboarding.flow.FlowDefinition;
import com.zalando.onboarding.flow.FlowDefinitionRepository;
import com.zalando.onboarding.flow.SectionDefinition;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Mocked identity and debt checks. No eID, no credit bureau, no registry — those are out of
 * scope, and nothing here should be mistaken for one.
 *
 * <p><strong>Deterministic by construction.</strong> Every input is the stored application,
 * the country's flow definition and configured demo data; there is no {@code Random} and no
 * dependence on wall-clock timing. The same application decided twice yields the same
 * confidence, the same flags and the same outcome — which is the only thing that makes tests
 * around decisioning worth writing.
 *
 * <p>The signals are deliberately ones an application can contradict itself on, because that
 * is all a mock can honestly look at:
 *
 * <ul>
 *   <li>the payout account not naming the applicant</li>
 *   <li>the IBAN belonging to a different country from the registration</li>
 *   <li>a tax identifier appearing in the demo debt register</li>
 * </ul>
 *
 * <p>Note the second is <em>not</em> a rejection. A12 is explicit that a cross-border IBAN is
 * legitimate — SEPA makes a German sole trader with a French account ordinary — so it lowers
 * confidence and can ask for a human, and never refuses on its own.
 *
 * <p>Which field holds the IBAN and which hold tax identifiers is read from the flow
 * definition, not hardcoded per country: the IBAN is whichever field declares the
 * {@code iban} validator, and the tax identifiers are the text fields of the tax section. A
 * fourth market therefore needs no change here.
 */
@Component
public class MockDecisionEngine implements DecisionEngine {

    private static final int FULL_CONFIDENCE = 100;

    /** The payout account naming somebody else is the strongest signal available to a mock. */
    private static final int NAME_MISMATCH_PENALTY = 40;

    /** Legitimate under SEPA, but worth a second look when combined with anything else. */
    private static final int FOREIGN_IBAN_PENALTY = 25;

    private static final String IBAN_VALIDATOR = "iban";

    /**
     * Present in every shipped flow. If a future market renames them the mock loses this
     * signal and says so by not applying a penalty, rather than guessing or throwing.
     */
    private static final String FIRST_NAME = "firstName";
    private static final String LAST_NAME = "lastName";
    private static final String ACCOUNT_HOLDER = "accountHolder";

    private final FlowDefinitionRepository flows;
    private final DecisionProperties properties;
    private final Clock clock;

    public MockDecisionEngine(FlowDefinitionRepository flows, DecisionProperties properties, Clock clock) {
        this.flows = flows;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Decision decide(Application application) {
        FlowDefinition flow = flows.findByCountry(application.getCountry());
        Map<String, Object> formData = application.getFormData();

        int confidence = FULL_CONFIDENCE;
        if (!accountHolderNamesApplicant(flow, formData)) {
            confidence -= NAME_MISMATCH_PENALTY;
        }
        if (ibanIsForeign(flow, formData, application.getCountry())) {
            confidence -= FOREIGN_IBAN_PENALTY;
        }

        List<String> debtFlags = debtFlags(flow, formData);

        return new Decision(confidence, debtFlags, outcome(confidence, debtFlags), Instant.now(clock));
    }

    /**
     * A hard flag decides on its own; confidence only decides in its absence. Ordering them
     * the other way would let a high-confidence application walk past a debt flag.
     */
    private FinalDecision outcome(int confidence, List<String> debtFlags) {
        if (!debtFlags.isEmpty()) {
            return FinalDecision.DECLINE;
        }
        return confidence < properties.referBelowConfidence() ? FinalDecision.REFER : FinalDecision.APPROVE;
    }

    /**
     * Whether the payout account holder is plausibly the applicant.
     *
     * <p>Token containment rather than string equality, so "Dr. Ada Lovelace" and
     * "Lovelace, Ada" both match "Ada" + "Lovelace". Accents are folded, so a form filled in
     * without a German keyboard does not read as a different person.
     *
     * <p>Returns true when any of the three fields is missing: absent evidence is not evidence
     * of a mismatch.
     */
    private boolean accountHolderNamesApplicant(FlowDefinition flow, Map<String, Object> formData) {
        Optional<String> holder = fieldValue(flow, formData, OnboardingStep.PAYMENT_DETAILS, ACCOUNT_HOLDER);
        Optional<String> first = fieldValue(flow, formData, OnboardingStep.PERSONAL_DETAILS, FIRST_NAME);
        Optional<String> last = fieldValue(flow, formData, OnboardingStep.PERSONAL_DETAILS, LAST_NAME);
        if (holder.isEmpty() || first.isEmpty() || last.isEmpty()) {
            return true;
        }
        Set<String> holderTokens = tokens(holder.get());
        return holderTokens.containsAll(tokens(first.get())) && holderTokens.containsAll(tokens(last.get()));
    }

    /** The IBAN's own country prefix against the country the business is registered in. */
    private boolean ibanIsForeign(FlowDefinition flow, Map<String, Object> formData, Country country) {
        return ibanValue(flow, formData)
                .map(iban -> iban.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT))
                .filter(letters -> letters.length() >= 2)
                .map(letters -> !letters.startsWith(country.name()))
                .orElse(false);
    }

    private List<String> debtFlags(FlowDefinition flow, Map<String, Object> formData) {
        Set<String> register = new LinkedHashSet<>();
        properties.debtRegisterTaxIds().forEach(id -> register.add(bareIdentifier(id)));

        boolean matched = taxIdentifiers(flow, formData).stream().anyMatch(register::contains);
        return matched ? List.of(Decision.DEBT_REGISTER_MATCH) : List.of();
    }

    /** The text fields of this country's tax section, whatever they happen to be called. */
    private List<String> taxIdentifiers(FlowDefinition flow, Map<String, Object> formData) {
        SectionDefinition tax = flow.section(OnboardingStep.TAX_INFORMATION).orElse(null);
        if (tax == null) {
            return List.of();
        }
        Map<String, Object> stored = sectionData(formData, tax.id());
        List<String> identifiers = new ArrayList<>();
        for (FieldDefinition field : tax.fields()) {
            if (field.type() != FieldType.TEXT) {
                continue;
            }
            Object value = stored.get(field.name());
            if (value != null) {
                identifiers.add(bareIdentifier(String.valueOf(value)));
            }
        }
        return identifiers;
    }

    private Optional<String> ibanValue(FlowDefinition flow, Map<String, Object> formData) {
        for (SectionDefinition section : flow.sections()) {
            for (FieldDefinition field : section.fields()) {
                if (field.validators().contains(IBAN_VALIDATOR)) {
                    Object value = sectionData(formData, section.id()).get(field.name());
                    if (value != null) {
                        return Optional.of(String.valueOf(value));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> fieldValue(FlowDefinition flow, Map<String, Object> formData,
                                        OnboardingStep step, String fieldName) {
        return flow.section(step)
                .map(section -> sectionData(formData, section.id()).get(fieldName))
                .map(String::valueOf)
                .filter(value -> !value.isBlank());
    }

    private Set<String> tokens(String value) {
        String folded = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ");
        return new LinkedHashSet<>(Arrays.stream(folded.trim().split("\\s+"))
                .filter(token -> !token.isEmpty())
                .toList());
    }

    private String bareIdentifier(String value) {
        return value.replaceAll("[\\s-/.]", "").toUpperCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sectionData(Map<String, Object> formData, String sectionId) {
        Object section = formData.get(sectionId);
        return section instanceof Map ? (Map<String, Object>) section : Map.of();
    }
}
