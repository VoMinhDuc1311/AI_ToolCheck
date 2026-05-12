package com.aitoolcheck.ai_toolcheck1_backend.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum PriorityLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    @JsonCreator
    public static PriorityLevel fromString(String value) {
        if (value == null) return null;
        try {
            return PriorityLevel.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid value for PriorityLevel: '" + value + "'");
        }
    }
}
