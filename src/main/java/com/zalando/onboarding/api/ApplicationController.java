package com.zalando.onboarding.api;

import com.zalando.onboarding.domain.Application;
import com.zalando.onboarding.domain.ApplicationService;
import com.zalando.onboarding.domain.ApplicationService.DraftCreated;
import com.zalando.onboarding.flow.FlowDefinition;
import com.zalando.onboarding.flow.ResumeStep;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transport only. Every rule about ordering, gating, progress and submission lives in
 * {@link ApplicationService}; what happens here is reading the request, calling one service
 * method, and shaping the result. Anything resembling an {@code if} about application state
 * in this class would be a rule the service could not enforce for other callers.
 *
 * <p><strong>The draft token is the path identifier because it IS the credential.</strong>
 * There is no authentication anywhere in this system (T-3), so the unguessable token is both
 * the name of the resource and the proof you may see it — which is exactly why there is no
 * endpoint that takes an applicationId, and why the token is never logged.
 */
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private final ApplicationService applications;

    public ApplicationController(ApplicationService applications) {
        this.applications = applications;
    }

    @PostMapping
    public ResponseEntity<CreateApplicationResponse> create(@RequestBody CreateApplicationRequest request) {
        DraftCreated created = applications.createDraft(request.email(), request.country());

        CreateApplicationResponse body = new CreateApplicationResponse(
                created.application().getId(), created.draftToken(), created.resumeUrl(), created.flow());

        // Location points at the token-addressed resource, which is the only way back in.
        return ResponseEntity.created(URI.create("/api/applications/" + created.draftToken())).body(body);
    }

    @GetMapping("/{draftToken}")
    public ApplicationView get(@PathVariable String draftToken) {
        Application application = applications.loadByDraftToken(draftToken);
        FlowDefinition flow = applications.flowFor(application);

        return new ApplicationView(
                application.getStatus(),
                application.getCountry(),
                application.getApplicantEmail(),
                application.getLastCompletedStep().orElse(null),
                resumeStep(application, flow),
                application.getReference().orElse(null),
                application.getSubmittedAt().orElse(null),
                application.getFormData(),
                flow);
    }

    @PutMapping("/{draftToken}/sections/{sectionId}")
    public SaveSectionResponse saveSection(@PathVariable String draftToken,
                                           @PathVariable String sectionId,
                                           @RequestBody(required = false) Map<String, Object> payload) {
        Application application = applications.saveSection(draftToken, sectionId, payload);
        FlowDefinition flow = applications.flowFor(application);

        return new SaveSectionResponse(
                application.getLastCompletedStep().orElse(null),
                resumeStep(application, flow),
                new SavedSection(sectionId, sectionData(application, sectionId)));
    }

    @PostMapping("/{draftToken}/submit")
    public SubmitResponse submit(@PathVariable String draftToken) {
        Application application = applications.submit(draftToken);

        return new SubmitResponse(
                application.getReference().orElseThrow(),
                application.getStatus(),
                application.getSubmittedAt().orElseThrow());
    }

    @GetMapping("/reference/{reference}")
    public SubmittedApplicationView getByReference(@PathVariable String reference) {
        Application application = applications.findByReference(reference);

        return new SubmittedApplicationView(
                application.getReference().orElseThrow(),
                application.getStatus(),
                application.getSubmittedAt().orElseThrow(),
                application.getCountry(),
                application.getApplicantEmail(),
                application.getFormData(),
                applications.flowFor(application));
    }

    /** Derived here, on the way out, from the fact the row stores. Never persisted. */
    private ResumeStep resumeStep(Application application, FlowDefinition flow) {
        return flow.resumeStepAfter(application.getLastCompletedStep().orElse(null));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sectionData(Application application, String sectionId) {
        return (Map<String, Object>) application.getFormData().get(sectionId);
    }
}
