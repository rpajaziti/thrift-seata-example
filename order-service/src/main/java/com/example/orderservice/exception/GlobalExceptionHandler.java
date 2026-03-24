package com.example.orderservice.exception;

import com.example.common.exception.ResourceNotFoundException;
import com.example.thrift.wallet.TWalletException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Resource Not Found");
        return problem;
    }

    @ExceptionHandler(TWalletException.class)
    public ProblemDetail handleWalletException(TWalletException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getCode());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle("Wallet Service Error");
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleException(Exception ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, root.getMessage());
        problem.setTitle("Internal Error");
        return problem;
    }
}
