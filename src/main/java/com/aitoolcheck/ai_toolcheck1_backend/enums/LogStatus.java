package com.aitoolcheck.ai_toolcheck1_backend.enums;

/**
 * Trạng thái của một lần AI Inference được ghi lại trong {@code LegacyInferenceLog}.
 * <ul>
 *   <li>{@link #SUCCESS} – Parser đã trích xuất và lưu thành công.</li>
 *   <li>{@link #FAILED}  – Có lỗi xảy ra trong quá trình parse hoặc lưu.</li>
 * </ul>
 */
public enum LogStatus {
    SUCCESS,
    FAILED
}
