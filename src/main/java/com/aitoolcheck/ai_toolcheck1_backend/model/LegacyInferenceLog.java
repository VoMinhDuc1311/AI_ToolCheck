package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.LogStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Audit Trail Entity — ghi lại toàn bộ vết của mỗi lần AI inference.
 *
 * <h3>Mục đích từng trường quan trọng</h3>
 * <ul>
 *   <li>{@code rawResponse}          – Dữ liệu thô từ Gemini trước khi Parser xử lý.
 *       Dùng để debug khi Parser cắt sai.</li>
 *   <li>{@code inferredMetadataJson} – JSON đã làm sạch (output Task 1).</li>
 *   <li>{@code status}               – SUCCESS / FAILED để lọc trên Dashboard.</li>
 *   <li>{@code errorType}            – ErrorType cụ thể (ví dụ: DTO_VALIDATION_FAILED).</li>
 *   <li>{@code version}              – Optimistic locking cho concurrent write.</li>
 * </ul>
 */
@Entity
@Table(
    name = "legacy_inference_log",
    indexes = {
        @Index(name = "idx_legacy_log_project_id",    columnList = "project_id"),
        @Index(name = "idx_legacy_log_endpoint_id",   columnList = "api_endpoint_id"),
        @Index(name = "idx_legacy_log_created_at",    columnList = "created_at"),
        @Index(name = "idx_legacy_log_status",        columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LegacyInferenceLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // ─── AI Data Fields ───────────────────────────────────────────────────────

    /** Raw text trả về từ Gemini — chưa qua bất kỳ xử lý nào. Dùng để debug. */
    @Column(name = "raw_response", columnDefinition = "LONGTEXT")
    private String rawResponse;

    /** JSON đã làm sạch (output của extractAndSanitizeJson — Task 1). */
    @Column(name = "inferred_metadata_json", columnDefinition = "LONGTEXT")
    private String inferredMetadataJson;

    @Column(name = "confidence_score")
    private Integer confidenceScore;

    // ─── Audit Fields ─────────────────────────────────────────────────────────

    /** Kết quả tổng thể của lần inference này. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private LogStatus status;

    /** ErrorType cụ thể nếu thất bại (ví dụ: INVALID_JSON_SYNTAX, DTO_VALIDATION_FAILED). */
    @Column(name = "error_type", length = 60)
    private String errorType;

    /** Optimistic locking — ngăn concurrent write ghi đè nhau. */
    @Version
    @Column(name = "version")
    private Integer version;

    /** Tự động ghi timestamp khi Entity được tạo lần đầu. */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // ─── Relationships ────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", referencedColumnName = "id", nullable = false)
    private SourceProject sourceProject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_file_id", referencedColumnName = "id")
    private SourceFile sourceFile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_endpoint_id", referencedColumnName = "id")
    private ApiEndpoint apiEndpoint;
}

