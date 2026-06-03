package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiProviderFailureException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiJsonParserServiceImplTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final AiJsonParserServiceImpl parser = new AiJsonParserServiceImpl(
            new ObjectMapper(),
            validator,
            new SimpleMeterRegistry());

    @Test
    void extractAndSanitizeJson_whenRawResponseIsNullThrowsEmptyResponseNotInvalidJson() {
        AiProviderFailureException exception = assertThrows(
                AiProviderFailureException.class,
                () -> parser.extractAndSanitizeJson(null));

        assertEquals(AiProviderFailureException.LLM_EMPTY_RESPONSE, exception.getErrorCode());
    }

    @Test
    void extractAndSanitizeJson_whenMalformedRawTextExistsThrowsInvalidJsonSyntax() {
        AiJsonParseException exception = assertThrows(
                AiJsonParseException.class,
                () -> parser.extractAndSanitizeJson("{not valid json}"));

        assertEquals(AiJsonParseException.ErrorType.INVALID_JSON_SYNTAX, exception.getErrorType());
    }

    @Test
    void extractAndSanitizeJson_whenFencedJsonExtractsSuccessfully() {
        String normalized = parser.extractAndSanitizeJson("""
                ```json
                {"summary":"ok"}
                ```
                """);

        assertEquals("{\"summary\":\"ok\"}", normalized);
    }
}
