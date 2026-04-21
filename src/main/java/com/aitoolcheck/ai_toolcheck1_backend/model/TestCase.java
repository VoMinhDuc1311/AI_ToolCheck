package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.CaseType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.GeneratedBy;
import com.aitoolcheck.ai_toolcheck1_backend.enums.PriorityLevel;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "test_case")
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

    @Column(name = "case_code")
    private String caseCode;

    @Column(name = "case_name")
    private String caseName;

    @Column(name = "case_type")
    @Enumerated(EnumType.STRING)
    private CaseType caseType;

    @Column(name = "priority_level")
    @Enumerated(EnumType.STRING)
    private PriorityLevel priorityLevel;

    @Column(name = "generated_by")
    @Enumerated(EnumType.STRING)
    private GeneratedBy generatedBy;

    @Column(name = "active_flag")
    private Boolean activeFlag;

    // ── Relationships ──────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", referencedColumnName = "id", nullable = false)
    private SourceProject sourceProject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_endpoint_id", referencedColumnName = "id", nullable = false)
    private ApiEndpoint apiEndpoint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_document_version_id", referencedColumnName = "id")
    private ApiDocumentVersion apiDocumentVersion;

    // Inverse side: test_case_input holds the UNIQUE FK
    @OneToOne(mappedBy = "testCase", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private TestCaseInput testCaseInput;

    @OneToMany(mappedBy = "testCase", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<TestCaseAssertion> testCaseAssertions;

    @OneToMany(mappedBy = "testCase", fetch = FetchType.LAZY)
    private List<TestRunItem> testRunItems;
}
