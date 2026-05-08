package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiInferenceResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiTestCaseDto;
import com.aitoolcheck.ai_toolcheck1_backend.exception.AiJsonParseException;
import java.util.List;

/**
 * Contract for the AI response Sanitization + Parsing Pipeline.
 * <p>
 * Implementations must be stateless and thread-safe.
 * </p>
 *
 * <h3>Pipeline overview</h3>
 * 
 * <pre>
 * Raw AI Response
 *   └─ extractAndSanitizeJson()  → clean JSON String   (Layer 1–3, Task 1)
 *         └─ parseToDto()        → AiInferenceResultDto (Layer 4–6, Task 2)
 * </pre>
 */
public interface AiJsonParserService {

    /**
     * Layer 1–3 (Task 1): Extracts and sanitizes a JSON block from a raw LLM
     * response.
     * <ol>
     * <li>Layer 1 – Heuristic Extraction via {@code indexOf} /
     * {@code lastIndexOf}.</li>
     * <li>Layer 2 – Strict syntax check via {@code ObjectMapper.readTree()}.</li>
     * <li>Layer 3 – Normalization via {@code node.toString()}.</li>
     * </ol>
     *
     * @param rawAiResponse Raw text from the LLM (may contain markdown, prose,
     *                      etc.)
     * @return A valid, normalized JSON string.
     * @throws AiJsonParseException if a valid JSON block cannot be extracted or
     *                              parsed.
     */
    String extractAndSanitizeJson(String rawAiResponse);

    <T> T parseJson(String rawAiResponse, Class<T> targetType);

    /**
     * Layer 4–6 (Task 2): Deserializes a clean JSON string into a validated DTO.
     * <ol>
     * <li>Layer 4 – Jackson {@code readValue()} →
     * {@link AiInferenceResultDto}.</li>
     * <li>Layer 5 – Jakarta Bean Validation (@NotNull, @NotEmpty, @Valid
     * cascade).</li>
     * <li>Layer 6 – Micrometer metrics (ai.parse.success / ai.parse.fail).</li>
     * </ol>
     *
     * @param cleanJson Sanitized JSON string — output of
     *                  {@link #extractAndSanitizeJson}.
     * @return A fully validated {@link AiInferenceResultDto}.
     * @throws AiJsonParseException with classified {@code ErrorType} on any
     *                              failure.
     */
    AiInferenceResultDto parseToDto(String cleanJson);

    /**
     * Self-Healing: Làm sạch chuỗi JSON thô trả về từ AI (cắt markdown, tìm ranh giới mảng)
     * và parse thành danh sách {@link AiTestCaseDto}.
     *
     * @param rawJson Chuỗi thô từ AI (có thể chứa markdown, text rác).
     * @return Danh sách test case đã được parse.
     * @throws AiJsonParseException nếu không tìm thấy mảng JSON hợp lệ.
     */
    List<AiTestCaseDto> cleanAndParseTestCaseJson(String rawJson);
}
