package com.expense.expenseService.DTO;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;

/**
 * An amount literal that was refused while the request body was still being parsed. Carries only a fixed,
 * value-free message; the web layer renders it as 400 VALIDATION_FAILED on the {@code amount} field.
 */
public class InvalidAmountException extends JsonMappingException {

    public InvalidAmountException(JsonParser parser, String message) {
        super(parser, message);
    }
}
