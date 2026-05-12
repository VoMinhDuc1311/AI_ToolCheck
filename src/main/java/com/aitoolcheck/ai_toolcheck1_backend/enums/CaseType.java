package com.aitoolcheck.ai_toolcheck1_backend.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum CaseType {
    POSITIVE,
    NEGATIVE,
    BOUNDARY,
    VALIDATION,
    AUTHORIZATION,
    AUTHENTICATION,
    PERFORMANCE,
    INTEGRATION;

    @JsonCreator
    public static CaseType fromString(String value) {
        if (value == null) return null;
        try {
            return CaseType.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid value for CaseType: '" + value + "'");
        }
    }
}
