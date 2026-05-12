package com.aitoolcheck.ai_toolcheck1_backend.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum AssertionType {
    STATUS_CODE,
    JSON_PATH,
    RESPONSE_TIME,
    RESPONSE_TIME_MS,
    HEADER,
    BODY_CONTAINS,
    BODY_NOT_NULL;

    @JsonCreator
    public static AssertionType fromString(String value) {
        if (value == null) return null;
        try {
            return AssertionType.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid value for AssertionType: '" + value + "'");
        }
    }
}
