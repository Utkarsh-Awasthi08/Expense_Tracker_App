package com.expense.expenseService.Web;

import java.util.List;

/** Thrown when a request is well-formed JSON/HTTP but breaks a business rule; rendered as 400 VALIDATION_FAILED. */
public class RequestValidationException extends RuntimeException {

    private final transient List<FieldIssue> issues;

    public RequestValidationException(List<FieldIssue> issues) {
        super("Request validation failed", null, false, false);
        this.issues = List.copyOf(issues);
    }

    public List<FieldIssue> getIssues() {
        return issues;
    }
}
