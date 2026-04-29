package com.aitoolcheck.ai_toolcheck1_backend.exception;

/**
 * Exception ném ra khi lớp Persistence gặp lỗi khi lưu kết quả AI vào Database.
 * <p>
 * Phân biệt với {@link AiJsonParseException} (lỗi parse dữ liệu AI) —
 * {@code AiPersistenceException} đại diện cho lỗi ở tầng lưu trữ (DB, constraint, v.v.).
 * </p>
 * <p>
 * Consumer có thể bắt riêng exception này để quyết định chiến lược xử lý khác
 * (ví dụ: lưu tạm vào file log cục bộ thay vì bỏ qua hoàn toàn).
 * </p>
 */
public class AiPersistenceException extends RuntimeException {

    public AiPersistenceException(String message) {
        super(message);
    }

    public AiPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
