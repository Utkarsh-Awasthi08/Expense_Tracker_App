package com.expense.expenseService.Web;

/** One entry of {@code details[]} in a VALIDATION_FAILED error body. The message never echoes the rejected value. */
public record FieldIssue(String field, String message) {
}
