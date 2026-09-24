package com.expense.expenseService.DTO;

import com.expense.expenseService.Entities.Amounts;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Reads the request amount from the raw token text and applies {@link Amounts} straight away, so a literal like
 * {@code 1e999999999} is refused inside Jackson, before any DTO, entity or rounding exists. Accepts a JSON number
 * or a numeric string; anything else is a type mismatch. The result is already normalised (scale 2, HALF_UP).
 */
public class AmountDeserializer extends JsonDeserializer<BigDecimal> {

    @Override
    public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonToken token = parser.currentToken();
        if (token != JsonToken.VALUE_NUMBER_INT && token != JsonToken.VALUE_NUMBER_FLOAT
                && token != JsonToken.VALUE_STRING) {
            return (BigDecimal) context.handleUnexpectedToken(BigDecimal.class, parser);
        }
        // getText() returns the token exactly as written; unlike getDecimalValue() it does no numeric work.
        String literal = parser.getText();
        try {
            return Amounts.parse(literal);
        } catch (IllegalArgumentException e) {
            throw new InvalidAmountException(parser, e.getMessage());
        }
    }
}
