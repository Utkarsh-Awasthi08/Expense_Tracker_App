package com.expense.expenseService.Identity;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the caller's user id (the validated {@code X-User-Id}) into a controller method.
 * This is the ONLY way a controller learns who is calling: a user id is never read from a query parameter or a body.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUserId {
}
