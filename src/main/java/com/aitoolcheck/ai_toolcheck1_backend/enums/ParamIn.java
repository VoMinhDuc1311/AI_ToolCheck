package com.aitoolcheck.ai_toolcheck1_backend.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum ParamIn {
    PATH,
    QUERY,
    HEADER,
    BODY,
    FORM,
    COOKIE;

    @JsonCreator
    public static ParamIn fromString(String value) {
        if (value == null) return null;
        try {
            return ParamIn.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid value for ParamIn: '" + value + "'");
        }
    }
}
