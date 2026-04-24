package com.aitoolcheck.ai_toolcheck1_backend.dto.gemini.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeminiResponse {
    private List<Candidate> candidates;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Candidate {
        private Content content;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Content {
        private List<Part> parts;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Part {
        private String text;
    }

    /**
     * Trích xuất an toàn chuỗi text từ response của Gemini API.
     * Cần xử lý cẩn thận để tránh NullPointerException do JSON lồng nhau nhiều tầng.
     *
     * @return Chuỗi text sinh ra, hoặc null nếu không có kết quả hợp lệ.
     */
    public String extractText() {
        if (this.candidates == null || this.candidates.isEmpty()) {
            return null;
        }

        Candidate firstCandidate = this.candidates.get(0);
        if (firstCandidate == null || firstCandidate.getContent() == null) {
            return null;
        }

        List<Part> parts = firstCandidate.getContent().getParts();
        if (parts == null || parts.isEmpty()) {
            return null;
        }

        Part firstPart = parts.get(0);
        if (firstPart == null || firstPart.getText() == null) {
            return null;
        }

        return firstPart.getText();
    }
}
