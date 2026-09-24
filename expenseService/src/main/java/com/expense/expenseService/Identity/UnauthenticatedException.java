package com.expense.expenseService.Identity;

/** No validated identity is available for the request; rendered as 401 UNAUTHORIZED with WWW-Authenticate. */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException() {
        super("No authenticated user", null, false, false);
    }
}
