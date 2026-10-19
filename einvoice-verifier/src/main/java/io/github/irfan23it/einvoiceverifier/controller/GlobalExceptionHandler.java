package io.github.irfan23it.einvoiceverifier.controller;

import io.github.irfan23it.einvoiceverifier.dto.ApiError;
import io.github.irfan23it.einvoiceverifier.dto.VerificationResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Translates framework-level failures into this service's own contract, so a client never receives
 * a raw stack trace or an unexpected payload shape.
 *
 * <p>Note that Spring Boot's own {@code problemdetails} advice is deliberately left disabled,
 * otherwise it would outrank this class and answer malformed requests with a generic
 * {@code ProblemDetail} body instead.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** A body that is not valid JSON (or has wrongly-typed fields) is a pre-flight rejection, not a 500. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<VerificationResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        VerificationResponse body = new VerificationResponse(
            null,
            "REJECTED_INTERNAL",
            1,
            List.of("Malformed JSON payload: the request body could not be parsed against the invoice schema."),
            null,
            null
        );
        return ResponseEntity.badRequest().body(body);
    }

    /** Raised by the controller for invalid batch uploads (absent file, wrong extension, bad CSV header). */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex) {
        String reason = ex.getReason() != null ? ex.getReason() : "The request could not be processed.";
        return ResponseEntity.status(ex.getStatusCode()).body(new ApiError("REQUEST_REJECTED", reason));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
            .body(new ApiError("REJECTED_BATCH", "The uploaded CSV exceeds the permitted file size."));
    }
}
