package com.shop.backend.presentation.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import java.io.IOException;

/** Accepts only the JSON literals true/false: rejects "true", "TRUE", 1, 0 and anything else Jackson would coerce. */
public class StrictBooleanDeserializer extends JsonDeserializer<Boolean> {

    @Override
    public Boolean deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_TRUE) {
            return Boolean.TRUE;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return Boolean.FALSE;
        }
        throw MismatchedInputException.from(p, Boolean.class, "value must be a JSON true or false");
    }
}
