package com.example.gateway.exception;

import com.example.thrift.inventory.TInventoryException;
import com.example.thrift.wallet.TWalletException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TWalletException.class)
    public ProblemDetail handleWalletException(TWalletException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getCode());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle("Wallet Service Error");
        return problem;
    }

    @ExceptionHandler(TInventoryException.class)
    public ProblemDetail handleInventoryException(TInventoryException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getCode());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle("Inventory Service Error");
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

    @ExceptionHandler(HttpStatusCodeException.class)
    public ResponseEntity<String> handleHttpStatusCodeException(HttpStatusCodeException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(ex.getResponseBodyAsString());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof HttpStatusCodeException httpEx) {
                return handleHttpStatusCodeException(httpEx);
            }
            if (cause instanceof TWalletException walletEx) {
                return ResponseEntity.status(walletEx.getCode()).body(handleWalletException(walletEx));
            }
            if (cause instanceof TInventoryException inventoryEx) {
                return ResponseEntity.status(inventoryEx.getCode()).body(handleInventoryException(inventoryEx));
            }
            cause = cause.getCause();
        }

        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, root.getMessage());
        problem.setTitle("Internal Error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
}
