package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.AssertionType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "test_case_assertion",
        indexes = {
                @Index(name = "idx_test_case_assertion_test_case_id", columnList = "test_case_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestCaseAssertion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "assertion_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private AssertionType assertionType;

    @Column(name = "target_path", length = 500)
    private String targetPath;

    @Column(name = "operator", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private ComparisonOperator operator;

    @Column(name = "expected_value", columnDefinition = "TEXT")
    private String expectedValue;

    @Column(name = "enabled_flag", nullable = false)
    private Boolean enabledFlag;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_case_id", referencedColumnName = "id", nullable = false)
    private TestCase testCase;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();

        if (enabledFlag == null) {
            enabledFlag = true;
        }

        if (sortOrder == null) {
            sortOrder = 1;
        }

        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}