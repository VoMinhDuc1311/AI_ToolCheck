package com.aitoolcheck.ai_toolcheck1_backend.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum ComparisonOperator {
    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    GREATER_THAN_OR_EQUALS,
    LESS_THAN,
    LESS_THAN_OR_EQUALS,
    CONTAINS,
    NOT_CONTAINS,
    IS_NULL,
    IS_NOT_NULL,
    EXISTS,
    NOT_EXISTS,
    MATCHES_REGEX;

    @JsonCreator
    public static ComparisonOperator fromString(String value) {
        if (value == null) return null;
        try {
            return ComparisonOperator.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid value for ComparisonOperator: '" + value + "'");
        }
    }
}
