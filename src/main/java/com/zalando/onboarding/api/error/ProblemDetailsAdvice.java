package com.zalando.onboarding.api.error;

import com.zalando.onboarding.api.RequestIdFilter;
import com.zalando.onboarding.domain.ApplicationConflictException;
import com.zalando.onboarding.domain.ApplicationNotFoundException;
import com.zalando.onboarding.domain.ValidationFailedException;
import com.zalando.onboarding.validation.Violation;
import com.zalando.onboarding.validation.ViolationCode;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One error shape for the entire API: RFC 7807 {@code application/problem+json} carrying
 * {@code type, title, status, requestId, violations[]}.
 *
 * <p>Both validation layers render through here, so a client that can display a section save
 * failure can display a submit failure with the same code. So does everything Spring MVC
 * raises before a controller is reached — that is why this extends
 * {@link ResponseEntityExceptionHandler} rather than only handling our own exceptions: an
 * unreadable body or a wrong method must not fall through to a different shape.
 *
 * <p>{@code violations} is always present, empty where a problem is not about the payload,
 * so the client never has to test for the field's existence.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    @ExceptionHandler(ValidationFailedException.class)
    public ResponseEntity<ProblemDetail> onValidationFailed(ValidationFailedException e) {
        // Not logged at warn: a rejected postcode is the system working, not an incident.
        // The violations carry submitted values in neither the code nor the message.
        log.debug("Rejected request with {} violation(s)", e.getViolations().size());
        return problem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_ERROR,
                e.getTitle(), e.getViolations());
    }

    @ExceptionHandler(ApplicationNotFoundException.class)
    public ResponseEntity<ProblemDetail> onNotFound(ApplicationNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, "Not found", List.of());
    }

    @ExceptionHandler(ApplicationConflictException.class)
    public ResponseEntity<ProblemDetail> onConflict(ApplicationConflictException e) {
        return problem(HttpStatus.CONFLICT, ProblemTypes.CONFLICT,
                e.getTitle(), List.of(e.getViolation()));
    }

    /**
     * Two tabs saved different sections of one application at the same time. One blob, one
     * row, so the second writer loses (T-10); it is safe to retry because nothing was
     * written.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> onOptimisticLock(OptimisticLockingFailureException e) {
        log.info("Optimistic lock conflict; the caller is asked to re-read and retry");
        return problem(HttpStatus.CONFLICT, ProblemTypes.CONFLICT, "Concurrent modification",
                List.of(Violation.application(ViolationCode.CONCURRENT_MODIFICATION,
                        "This application changed while you were saving. Reload and try again")));
    }

    /**
     * The last resort. The message is dropped on purpose — it can name a table, a driver or
     * a value — and the requestId is what ties the response to the stack trace in the log.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> onUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL_ERROR,
                "Internal server error", List.of());
    }

    /**
     * Everything Spring MVC itself raises: unreadable JSON, a wrong method, a missing path.
     * Routed through the same builder so the shape never varies by who raised the error.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body,
                                                             HttpHeaders headers, HttpStatusCode status,
                                                             WebRequest request) {
        List<Violation> violations = e instanceof HttpMessageNotReadableException
                ? List.of(Violation.application(ViolationCode.MALFORMED_REQUEST,
                        "The request could not be read"))
                : List.of();

        URI type = status.is4xxClientError()
                ? ProblemTypes.VALIDATION_ERROR
                : ProblemTypes.INTERNAL_ERROR;

        String title = body instanceof ProblemDetail detail && detail.getTitle() != null
                ? detail.getTitle()
                : HttpStatus.valueOf(status.value()).getReasonPhrase();

        ResponseEntity<ProblemDetail> problem = problem(status, type, title, violations);
        return ResponseEntity.status(status).headers(problem.getHeaders()).body(problem.getBody());
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatusCode status, URI type, String title,
                                                  List<Violation> violations) {
        ProblemDetail detail = ProblemDetail.forStatus(status);
        detail.setType(type);
        detail.setTitle(title);
        // Every response carries the requestId, so support can find this exact call.
        detail.setProperty("requestId", RequestIdFilter.current());
        detail.setProperty("violations", violations);
        return ResponseEntity.status(status).body(detail);
    }
}
