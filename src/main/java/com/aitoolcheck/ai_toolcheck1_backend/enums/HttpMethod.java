package com.aitoolcheck.ai_toolcheck1_backend.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum HttpMethod {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
    OPTIONS,
    HEAD;

    @JsonCreator
    public static HttpMethod fromString(String value) {
        if (value == null)
            return null;
        try {
            return HttpMethod.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid value for HttpMethod: '" + value + "'");
        }
    }
}
