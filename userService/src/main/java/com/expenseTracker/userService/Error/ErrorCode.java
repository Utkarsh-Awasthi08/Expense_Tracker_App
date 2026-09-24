package com.expenseTracker.userService.Error;

import org.springframework.http.HttpStatus;

/** The pinned error codes of docs/CONTRACTS.md (all services share the same set). */
public enum ErrorCode {
    UNAUTHORIZED,
    FORBIDDEN,
    VALIDATION_FAILED,
    NOT_FOUND,
    CONFLICT,
    BAD_REQUEST,
    BATCH_TOO_LARGE,
    INTERNAL;

    /** Default code for a status the service did not classify itself (framework errors). */
    public static ErrorCode forStatus(int status) {
        if (status == HttpStatus.UNAUTHORIZED.value()) {
            return UNAUTHORIZED;
        }
        if (status == HttpStatus.FORBIDDEN.value()) {
            return FORBIDDEN;
        }
        if (status == HttpStatus.NOT_FOUND.value()) {
            return NOT_FOUND;
        }
        if (status == HttpStatus.CONFLICT.value()) {
            return CONFLICT;
        }
        if (status == HttpStatus.PAYLOAD_TOO_LARGE.value()) {
            return BATCH_TOO_LARGE;
        }
        return status >= 500 ? INTERNAL : BAD_REQUEST;
    }
}
