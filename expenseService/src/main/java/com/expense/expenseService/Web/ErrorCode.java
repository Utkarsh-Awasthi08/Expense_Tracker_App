package com.expense.expenseService.Web;

/** The pinned error codes (docs/CONTRACTS.md). BATCH_TOO_LARGE is part of the shared vocabulary but unused here. */
public enum ErrorCode {
    UNAUTHORIZED, FORBIDDEN, VALIDATION_FAILED, NOT_FOUND, CONFLICT, BAD_REQUEST, BATCH_TOO_LARGE, INTERNAL
}
