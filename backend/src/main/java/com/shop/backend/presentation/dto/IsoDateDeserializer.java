package com.shop.backend.presentation.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Strict "yyyy-MM-dd" only: rejects numbers, arrays, datetimes and padded strings. "" means "no value".
 * Error messages deliberately omit the input (it may be a birth date).
 */
public class IsoDateDeserializer extends JsonDeserializer<LocalDate> {

    @Override
    public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() != JsonToken.VALUE_STRING) {
            throw MismatchedInputException.from(p, LocalDate.class, "date must be a yyyy-MM-dd string");
        }
        String text = p.getText();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text); // ISO_LOCAL_DATE, strict resolver
        } catch (DateTimeParseException ex) {
            throw MismatchedInputException.from(p, LocalDate.class, "date must be a yyyy-MM-dd string");
        }
    }
}
