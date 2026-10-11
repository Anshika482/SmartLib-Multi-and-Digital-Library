package com.smartlib.common.error;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.smartlib.common.web.RequestIdFilter;

/** Translates every exception into a consistent RFC 9457 problem response with a stable {@code code}. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex, HttpServletRequest request) {
        ProblemDetail pd = problem(ex.getStatus(), ex.getCode(), ex.getMessage(), request);
        ex.getDetails().forEach(pd::setProperty);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        Object retry = ex.getDetails().get("retryAfterSeconds");
        if (ex.getStatus() == HttpStatus.TOO_MANY_REQUESTS && retry != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(retry));
        }
        return builder.body(pd);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleInvalid(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", fe.getDefaultMessage() == null ? "Invalid value" : fe.getDefaultMessage()))
                .toList();
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "Please correct the highlighted fields.", request);
        pd.setProperty("fieldErrors", errors);
        return respond(HttpStatus.BAD_REQUEST, pd);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(e -> Map.of("field", r.getMethodParameter().getParameterName() == null ? "param" : r.getMethodParameter().getParameterName(),
                                "message", e.getDefaultMessage() == null ? "Invalid value" : e.getDefaultMessage())))
                .toList();
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "Invalid request parameters.", request);
        pd.setProperty("fieldErrors", errors);
        return respond(HttpStatus.BAD_REQUEST, pd);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraint(ConstraintViolationException ex, HttpServletRequest request) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(v -> Map.of("field", String.valueOf(v.getPropertyPath()), "message", v.getMessage()))
                .toList();
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "Invalid request.", request);
        pd.setProperty("fieldErrors", errors);
        return respond(HttpStatus.BAD_REQUEST, pd);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestPartException.class, MissingRequestHeaderException.class})
    public ResponseEntity<ProblemDetail> handleMalformed(Exception ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, problem(HttpStatus.BAD_REQUEST, ErrorCodes.MALFORMED_REQUEST,
                "The request could not be understood. Please check the submitted data.", request));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaType(Exception ex, HttpServletRequest request) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCodes.MALFORMED_REQUEST,
                "Unsupported content type.", request));
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ProblemDetail> handleDenied(Exception ex, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, problem(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN,
                "You do not have permission to perform this action.", request));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimistic(Exception ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, problem(HttpStatus.CONFLICT, ErrorCodes.CONCURRENT_UPDATE,
                "This record was changed by someone else. Please refresh and try again.", request));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation [{}]: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return respond(HttpStatus.CONFLICT, problem(HttpStatus.CONFLICT, ErrorCodes.CONFLICT,
                "The request conflicts with existing data.", request));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleTooLarge(Exception ex, HttpServletRequest request) {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, problem(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCodes.FILE_TOO_LARGE,
                "The uploaded file is too large.", request));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResource(Exception ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, problem(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "Not found.", request));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethod(Exception ex, HttpServletRequest request) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, problem(HttpStatus.METHOD_NOT_ALLOWED, ErrorCodes.METHOD_NOT_ALLOWED,
                "Method not allowed.", request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled error [{} {}]", request.getMethod(), request.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR,
                "Something went wrong on our side. Please try again.", request));
    }

    public static ProblemDetail problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", code);
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (requestId != null) {
            pd.setProperty("requestId", requestId);
        }
        return pd;
    }

    private static ResponseEntity<ProblemDetail> respond(HttpStatus status, ProblemDetail pd) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }
}
