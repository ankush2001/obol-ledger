package dev.ankush.obol.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns exceptions into RFC 9457 problem documents.
 *
 * <p>Every response carries a stable {@code code}, because a payments client
 * needs to branch on the failure, and an HTTP status alone cannot distinguish
 * "you are overdrawn" from "your currencies disagree" -- both are 422.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String BASE = "https://obol.ankushchoudhary.dev/problems/";

    @ExceptionHandler(LedgerException.class)
    public ProblemDetail onLedgerException(LedgerException ex, HttpServletRequest req) {
        // Expected, caller-visible outcomes: logged at INFO with no stack
        // trace. An overdraft attempt is the ledger doing its job, and
        // filling the error log with them hides the failures that matter.
        log.info("rejected {} {}: {} ({})", req.getMethod(), req.getRequestURI(), ex.code(), ex.getMessage());
        return problem(ex.status(), ex.code(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidationFailure(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        f -> f.getField(),
                        f -> f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage(),
                        (a, b) -> a));
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "validation_failed", "the request body is not valid");
        pd.setProperty("errors", fieldErrors);
        return pd;
    }

    /**
     * Lock timeouts and serialisation failures. The transaction did not
     * commit, so retrying the identical request with the same idempotency key
     * is safe -- and that is exactly what 503 plus Retry-After tells a
     * well-behaved client to do.
     */
    @ExceptionHandler({CannotAcquireLockException.class, ConcurrencyFailureException.class})
    public ProblemDetail onContention(Exception ex) {
        log.warn("contention, asking the caller to retry: {}", ex.getMessage());
        ProblemDetail pd = problem(HttpStatus.SERVICE_UNAVAILABLE, "contention",
                "the accounts involved are busy; retry with the same idempotency key");
        pd.setProperty("retryable", true);
        return pd;
    }

    /**
     * Spring's own web exceptions -- a missing required header, an unreadable
     * body, an unknown path, a method that is not allowed.
     *
     * <p>These already know their correct status, and every one of them is the
     * caller's mistake. Without this handler the catch-all below would
     * relabel all of them 500, which would tell a client to retry a request
     * that can never succeed. Wide handlers are how 4xx quietly becomes 5xx.
     */
    @ExceptionHandler({
            ServletRequestBindingException.class,      // missing header or request param
            ResponseStatusException.class,             // incl. HandlerMethodValidationException
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            NoResourceFoundException.class
    })
    public ResponseEntity<ProblemDetail> onSpringWebException(Exception ex, HttpServletRequest req) {
        // Each of these carries its own correct status via ErrorResponse; the
        // fallback only fires for something that does not, and 400 is the
        // right guess for a bucket that is entirely caller error.
        HttpStatus status = ex instanceof ErrorResponse er
                ? HttpStatus.valueOf(er.getStatusCode().value())
                : HttpStatus.BAD_REQUEST;

        String detail = ex instanceof ErrorResponse er && er.getBody().getDetail() != null
                ? er.getBody().getDetail()
                : ex.getMessage();

        log.info("rejected {} {}: {}", req.getMethod(), req.getRequestURI(), detail);
        return ResponseEntity.status(status)
                .body(problem(status, status.name().toLowerCase(), detail));
    }

    /** Malformed JSON. Not an ErrorResponse, so it needs its own handler. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail onUnreadableBody(HttpMessageNotReadableException ex) {
        log.info("unreadable request body: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "malformed_body",
                "the request body could not be parsed as JSON");
    }

    /** Violations on @RequestParam and @PathVariable rather than the body. */
    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail onParameterFailure(Exception ex) {
        log.info("invalid request parameter: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "invalid_parameter", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception ex, HttpServletRequest req) {
        // Unlike the handlers above, this one means the ledger surprised us.
        log.error("unhandled failure on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "the request could not be completed");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(BASE + code));
        pd.setTitle(code.replace('_', ' '));
        pd.setProperty("code", code);
        return pd;
    }
}
