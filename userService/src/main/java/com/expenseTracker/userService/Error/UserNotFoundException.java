package com.expenseTracker.userService.Error;

/** No profile row exists for the caller (the user.created event has not landed yet). */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException() {
        super("User profile not found");
    }
}
