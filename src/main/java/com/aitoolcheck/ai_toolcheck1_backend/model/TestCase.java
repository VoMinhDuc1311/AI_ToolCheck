package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.CaseType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.GeneratedBy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.PriorityLevel;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "test_case",
        indexes = {
                @Index(name = "idx_test_case_project_id", columnList = "project_id"),
                @Index(name = "idx_test_case_api_endpoint_id", columnList = "api_endpoint_id"),
                @Index(name = "idx_test_case_deleted_flag", columnList = "deleted_flag")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "case_code", length = 100)
    private String caseCode;

    @Column(name = "case_name", nullable = false, length = 150)
    private String caseName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "case_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private CaseType caseType;

    @Column(name = "priority_level", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private PriorityLevel priorityLevel;

    @Column(name = "generated_by", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private GeneratedBy generatedBy;

    @Column(name = "active_flag", nullable = false)
    private Boolean activeFlag;

    @Column(name = "deleted_flag", nullable = false)
    private Boolean deletedFlag;

    @Column(name = "requires_write", nullable = false)
    private Boolean requiresWrite;

    @Column(name = "cleanup_required", nullable = false)
    private Boolean cleanupRequired;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", referencedColumnName = "id", nullable = false)
    private SourceProject sourceProject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_endpoint_id", referencedColumnName = "id")
    private ApiEndpoint apiEndpoint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_document_version_id", referencedColumnName = "id")
    private ApiDocumentVersion apiDocumentVersion;

    @OneToOne(
            mappedBy = "testCase",
            fetch = FetchType.LAZY,
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private TestCaseInput testCaseInput;

    @OrderBy("sortOrder ASC")
    @OneToMany(
            mappedBy = "testCase",
            fetch = FetchType.LAZY,
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<TestCaseAssertion> testCaseAssertions = new ArrayList<>();

    @OneToMany(mappedBy = "testCase", fetch = FetchType.LAZY)
    private List<TestRunItem> testRunItems;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();

        if (activeFlag == null) {
            activeFlag = true;
        }

        if (deletedFlag == null) {
            deletedFlag = false;
        }

        if (requiresWrite == null) {
            requiresWrite = false;
        }

        if (cleanupRequired == null) {
            cleanupRequired = false;
        }

        if (caseType == null) {
            caseType = CaseType.POSITIVE;
        }

        if (priorityLevel == null) {
            priorityLevel = PriorityLevel.MEDIUM;
        }

        if (generatedBy == null) {
            generatedBy = GeneratedBy.USER;
        }

        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void assignInput(TestCaseInput input) {
        this.testCaseInput = input;
        if (input != null) {
            input.setTestCase(this);
        }
    }

    public void replaceAssertions(List<TestCaseAssertion> assertions) {
        if (this.testCaseAssertions == null) {
            this.testCaseAssertions = new ArrayList<>();
        }
        this.testCaseAssertions.clear();

        if (assertions == null) {
            return;
        }

        for (TestCaseAssertion assertion : assertions) {
            assertion.setTestCase(this);
            this.testCaseAssertions.add(assertion);
        }
    }

    public void softDelete() {
        this.deletedFlag = true;
        this.activeFlag = false;
        this.deletedAt = LocalDateTime.now();
    }
}