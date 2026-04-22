package com.aitoolcheck.ai_toolcheck1_backend.model;

import com.aitoolcheck.ai_toolcheck1_backend.enums.AssertionType;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ComparisonOperator;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "test_case_assertion")
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

    @Column(name = "assertion_type")
    @Enumerated(EnumType.STRING)
    private AssertionType assertionType;

    @Column(name = "target_path")
    private String targetPath;

    @Column(name = "operator")
    @Enumerated(EnumType.STRING)
    private ComparisonOperator operator;

    @Column(name = "expected_value")
    private String expectedValue;



    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_case_id", referencedColumnName = "id", nullable = false)
    private TestCase testCase;
}
