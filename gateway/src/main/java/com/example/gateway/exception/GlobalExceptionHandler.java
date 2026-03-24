package com.example.gateway.exception;

import com.example.common.exception.InsufficientBalanceException;
import com.example.common.exception.InsufficientStockException;
import com.example.common.exception.ResourceNotFoundException;
import org.apache.thrift.TException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Resource Not Found");
        return problem;
    }

    @ExceptionHandler({InsufficientBalanceException.class, InsufficientStockException.class})
    public ProblemDetail handleInsufficientResource(RuntimeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Insufficient Resource");
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, details);
        problem.setTitle("Validation Error");
        return problem;
    }

    @ExceptionHandler(TException.class)
    public ProblemDetail handleThriftException(TException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, ex.getMessage());
        problem.setTitle("Service Communication Error");
        return problem;
    }

    @ExceptionHandler(HttpStatusCodeException.class)
    public ProblemDetail handleHttpStatusCodeException(HttpStatusCodeException ex) {
        HttpStatusCode status = ex.getStatusCode();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getResponseBodyAsString());
        problem.setTitle("Downstream Service Error");
        return problem;
    }

    @ExceptionHandler(RestClientException.class)
    public ProblemDetail handleRestClientException(RestClientException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, ex.getMessage());
        problem.setTitle("Service Communication Error");
        return problem;
    }

    @ExceptionHandler(RuntimeException.class)
    public ProblemDetail handleRuntimeException(RuntimeException ex) {
        HttpStatusCodeException httpCause = findCause(ex, HttpStatusCodeException.class);
        if (httpCause != null) {
            return handleHttpStatusCodeException(httpCause);
        }

        TException thriftCause = findCause(ex, TException.class);
        if (thriftCause != null) {
            return handleThriftException(thriftCause);
        }

        Throwable root = getRootCause(ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, root.getMessage());
        problem.setTitle("Internal Error");
        return problem;
    }

    private <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
        Throwable cause = ex;
        while (cause != null) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
            cause = cause.getCause();
        }
        return null;
    }

    private Throwable getRootCause(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
