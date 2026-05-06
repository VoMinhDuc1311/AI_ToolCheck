package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "test_run_item",
        indexes = {
                @Index(name = "idx_test_run_item_test_run_id", columnList = "test_run_id"),
                @Index(name = "idx_test_run_item_test_case_id", columnList = "test_case_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestRunItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;


    @Column(name = "item_status", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private ExecutionStatus itemStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_run_id", referencedColumnName = "id", nullable = false)
    private TestRun testRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_case_id", referencedColumnName = "id", nullable = false)
    private TestCase testCase;


    @OneToOne(mappedBy = "testRunItem", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private TestResult testResult;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;

        if (itemStatus == null) {
            itemStatus = ExecutionStatus.PENDING;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
