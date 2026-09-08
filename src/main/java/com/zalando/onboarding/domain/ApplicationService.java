package com.zalando.onboarding.domain;

import com.zalando.onboarding.flow.FlowDefinition;
import com.zalando.onboarding.flow.FlowDefinitionRepository;
import com.zalando.onboarding.flow.SectionDefinition;
import com.zalando.onboarding.notification.NotificationPort;
import com.zalando.onboarding.notification.ResumeLinkIssued;
import com.zalando.onboarding.support.OnboardingProperties;
import com.zalando.onboarding.support.SecureTokenGenerator;
import com.zalando.onboarding.validation.SectionValidation;
import com.zalando.onboarding.validation.SectionValidator;
import com.zalando.onboarding.validation.SubmissionValidator;
import com.zalando.onboarding.validation.Violation;
import com.zalando.onboarding.validation.ViolationCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every rule about what an application may do next lives here.
 *
 * <p>Controllers below it are transport, validators beside it answer "is this value right",
 * and the entity under it refuses shapes that cannot exist. What sits only in this class is
 * the sequencing: which section may be written now, when progress moves, and how a draft
 * becomes a submission exactly once. None of that is safe to spread out, because there is no
 * authentication anywhere in this system — a client can call any endpoint in any order, so
 * ordering that is enforced by the frontend is not enforced at all.
 */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    /**
     * Deliberately permissive, and not RFC 5322. The only address this can usefully reject
     * is one that could not be delivered to under any reading; deciding anything finer than
     * that requires actually sending mail, which is out of scope (A18). A stricter regex
     * here would reject real addresses at the first step of a funnel built to measure
     * drop-off.
     */
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+$");

    private static final int EMAIL_MAX_LENGTH = 320;

    private final ApplicationRepository applications;
    private final FlowDefinitionRepository flows;
    private final SectionValidator sectionValidator;
    private final SubmissionValidator submissionValidator;
    private final SecureTokenGenerator tokens;
    private final NotificationPort notifications;
    private final OnboardingProperties properties;
    private final Clock clock;

    public ApplicationService(ApplicationRepository applications, FlowDefinitionRepository flows,
                              SectionValidator sectionValidator, SubmissionValidator submissionValidator,
                              SecureTokenGenerator tokens, NotificationPort notifications,
                              OnboardingProperties properties, Clock clock) {
        this.applications = applications;
        this.flows = flows;
        this.sectionValidator = sectionValidator;
        this.submissionValidator = submissionValidator;
        this.tokens = tokens;
        this.notifications = notifications;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Starts a new draft. Always a new one.
     *
     * <p>No lookup by email happens here, on purpose (A19). Resuming by email would mean
     * anyone who knows an applicant's address could read their name, date of birth, tax
     * number and IBAN, and the only honest fix — proving ownership of the address — needs
     * the notifications that are out of scope. The useful side effect is that the response
     * is identical whether or not a draft already exists, so this endpoint is not an
     * enumeration oracle.
     *
     * <p>The resume link is recorded in the same transaction as the draft, so the two cannot
     * disagree about whether the application exists.
     */
    @Transactional
    public DraftCreated createDraft(String email, String country) {
        String normalisedEmail = normaliseEmail(email);
        Country parsedCountry = parseCountry(country);

        String draftToken = tokens.newDraftToken();
        Application application = Application.newDraft(
                UUID.randomUUID(),
                tokens.hashDraftToken(draftToken),
                normalisedEmail,
                parsedCountry,
                Instant.now(clock));

        applications.save(application);

        String resumeUrl = properties.resumeUrlFor(draftToken);
        notifications.resumeLinkIssued(
                new ResumeLinkIssued(application.getId(), normalisedEmail, resumeUrl));

        log.info("Created draft application {} for country {}", application.getId(), parsedCountry);

        // The plaintext token leaves this method once and is never stored; only its hash is.
        return new DraftCreated(application, draftToken, resumeUrl, flowFor(application));
    }

    /** Resume. The token is hashed before it touches the database, and a miss is a 404. */
    @Transactional(readOnly = true)
    public Application loadByDraftToken(String draftToken) {
        return applications.findByDraftTokenHash(tokens.hashDraftToken(requireToken(draftToken)))
                .orElseThrow(() -> new ApplicationNotFoundException(
                        "No application matches the draft token presented"));
    }

    /**
     * Stores one completed section, if the application is allowed to receive it.
     *
     * <p>The checks run in this order and each one is a different answer to the client:
     * is this application writable at all (409), is this a section of its flow (400), is it
     * reachable yet (409), are the values valid (400). Validation is last because there is
     * no point telling somebody their postcode is malformed for a step they cannot reach.
     */
    @Transactional
    public Application saveSection(String draftToken, String sectionId, Map<String, Object> payload) {
        Application application = loadByDraftToken(draftToken);
        requireDraft(application);

        FlowDefinition flow = flowFor(application);
        SectionDefinition section = flow.section(sectionId)
                .orElseThrow(() -> ValidationFailedException.of("Unknown section",
                        Violation.section(sectionId, ViolationCode.SECTION_UNKNOWN,
                                "The " + application.getCountry() + " flow has no section called '"
                                        + sectionId + "'")));

        OnboardingStep advanceTo = gate(application, flow, section);

        SectionValidation validation = sectionValidator.validate(section, payload);
        if (!validation.isValid()) {
            // Thrown before any write. Nothing is persisted when layer 1 fails.
            throw new ValidationFailedException("Validation failed", validation.violations());
        }

        application.completeSection(sectionId, validation.values(), advanceTo, Instant.now(clock));
        applications.save(application);

        log.info("Saved section {} of application {}; lastCompletedStep is now {}",
                sectionId, application.getId(), application.getLastCompletedStep().orElse(null));

        return application;
    }

    /**
     * Forward gating, and the decision about whether this save moves progress.
     *
     * <p>Both halves come out of one index comparison because they are one rule seen from two
     * sides: a section is reachable when it is at most one past the furthest completed, and
     * progress moves only when it is exactly one past. Re-saving anything earlier is allowed
     * and changes nothing, which is what stops {@code last_completed_step} from ever going
     * backward (A4).
     *
     * @return the step to record as furthest completed, or null to leave progress alone
     */
    private OnboardingStep gate(Application application, FlowDefinition flow, SectionDefinition section) {
        List<OnboardingStep> order = flow.steps();
        int target = order.indexOf(section.step());

        int furthest = application.getLastCompletedStep()
                .map(step -> indexIn(order, step, application))
                .orElse(-1);
        int next = furthest + 1;

        if (target > next) {
            OnboardingStep blocking = order.get(next);
            throw new ApplicationConflictException("Section not reachable yet",
                    Violation.section(section.id(), ViolationCode.SECTION_NOT_REACHABLE,
                            "Complete the " + flow.section(blocking).orElseThrow().id()
                                    + " section before this one"));
        }
        return target == next ? section.step() : null;
    }

    private int indexIn(List<OnboardingStep> order, OnboardingStep step, Application application) {
        int index = order.indexOf(step);
        if (index < 0) {
            // Only reachable if a shipped flow definition dropped a section that live drafts
            // had already completed — the limitation A21 records. A 500 is the honest answer.
            throw new IllegalStateException("Application " + application.getId() + " has completed "
                    + step + ", which the " + application.getCountry() + " flow no longer contains");
        }
        return index;
    }

    /**
     * Submits, once, whatever happens.
     *
     * <p>An already-submitted application returns its existing reference rather than failing:
     * a double-click is not an error, and it must not mint a second reference (A10). The
     * transition itself is a conditional UPDATE, so even two simultaneous first submits
     * resolve to one winner and one reader.
     */
    @Transactional
    public Application submit(String draftToken) {
        Application application = loadByDraftToken(draftToken);

        if (!application.isDraft()) {
            log.info("Submit against already submitted application {}; returning existing reference {}",
                    application.getId(), application.getReference().orElse(null));
            return application;
        }

        FlowDefinition flow = flowFor(application);
        List<Violation> violations = submissionValidator.validate(application, flow);
        if (!violations.isEmpty()) {
            throw new ValidationFailedException("Validation failed", violations);
        }
        requireEmailNotAlreadySubmitted(application);

        // Generated inside the transition, never before it: a reference minted ahead of a
        // losing UPDATE would be a reference that was handed out and never stored.
        int rows = transition(application);
        Application submitted = applications.findById(application.getId()).orElseThrow(
                () -> new IllegalStateException("Application " + application.getId()
                        + " disappeared during submission"));

        if (rows == 1) {
            log.info("Submitted application {} as reference {}",
                    submitted.getId(), submitted.getReference().orElse(null));
        } else {
            log.info("Lost the submit race for application {}; returning existing reference {}",
                    submitted.getId(), submitted.getReference().orElse(null));
        }
        return submitted;
    }

    private int transition(Application application) {
        try {
            return applications.markSubmitted(
                    application.getId(), tokens.newReference(), Instant.now(clock));
        } catch (DataIntegrityViolationException e) {
            // The partial unique index on submitted emails. The pre-check above closes the
            // ordinary case; this closes the race between two drafts submitting at once.
            throw emailAlreadySubmitted();
        }
    }

    private void requireEmailNotAlreadySubmitted(Application application) {
        if (applications.existsByApplicantEmailAndStatusAndIdNot(
                application.getApplicantEmail(), ApplicationStatus.SUBMITTED, application.getId())) {
            throw emailAlreadySubmitted();
        }
    }

    private ApplicationConflictException emailAlreadySubmitted() {
        return new ApplicationConflictException("Email already submitted",
                Violation.field("email", ViolationCode.EMAIL_ALREADY_SUBMITTED,
                        "An application has already been submitted with this email address"));
    }

    /** Retrieval by the reference handed out at submission (FR5). Read-only by construction. */
    @Transactional(readOnly = true)
    public Application findByReference(String reference) {
        String normalised = reference == null ? "" : reference.trim().toUpperCase(Locale.ROOT);
        return applications.findByReference(normalised)
                .orElseThrow(() -> new ApplicationNotFoundException(
                        "No submitted application matches the reference presented"));
    }

    public FlowDefinition flowFor(Application application) {
        return flows.findByCountry(application.getCountry());
    }

    private void requireDraft(Application application) {
        if (!application.isDraft()) {
            throw new ApplicationConflictException("Application already submitted",
                    Violation.application(ViolationCode.APPLICATION_ALREADY_SUBMITTED,
                            "This application has been submitted and can no longer be changed"));
        }
    }

    /**
     * Trim, lowercase, reject what could not be an address.
     *
     * <p>Lowercasing the local part is technically lossy — RFC 5321 lets it be
     * case-sensitive — and is done anyway: every mail provider an applicant is realistically
     * using treats it as case-insensitive, and without it the submitted-email uniqueness rule
     * (A7) would be defeated by capitalising one letter.
     */
    private String normaliseEmail(String email) {
        String normalised = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (normalised.isEmpty()) {
            throw ValidationFailedException.of("Validation failed",
                    Violation.field("email", ViolationCode.REQUIRED, "An email address is required"));
        }
        if (normalised.length() > EMAIL_MAX_LENGTH || !EMAIL.matcher(normalised).matches()) {
            throw ValidationFailedException.of("Validation failed",
                    Violation.field("email", ViolationCode.PATTERN_INVALID,
                            "Not a valid email address"));
        }
        return normalised;
    }

    /** Country selects the flow, so an unknown one has no flow to run and cannot be accepted. */
    private Country parseCountry(String country) {
        String candidate = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
        return Optional.of(candidate)
                .filter(value -> !value.isEmpty())
                .flatMap(ApplicationService::countryOf)
                .orElseThrow(() -> ValidationFailedException.of("Validation failed",
                        Violation.field("country", ViolationCode.COUNTRY_UNKNOWN,
                                "Onboarding is available in DE, PL and NL")));
    }

    public static Optional<Country> countryOf(String value) {
        String candidate = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        for (Country country : Country.values()) {
            if (country.name().equals(candidate)) {
                return Optional.of(country);
            }
        }
        return Optional.empty();
    }

    private String requireToken(String draftToken) {
        if (draftToken == null || draftToken.isBlank()) {
            throw new ApplicationNotFoundException("No draft token was presented");
        }
        return draftToken;
    }

    /**
     * @param draftToken the plaintext, returned exactly once. It is not recoverable later:
     *                   only its hash is stored
     */
    public record DraftCreated(Application application, String draftToken, String resumeUrl,
                               FlowDefinition flow) {
    }
}
