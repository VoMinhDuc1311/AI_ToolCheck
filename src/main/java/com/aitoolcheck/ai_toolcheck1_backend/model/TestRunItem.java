package com.aitoolcheck.ai_toolcheck1_backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "test_run_item")
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



    // This entity acts as the explicit join table for the test_run <-> test_case M-N
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_run_id", referencedColumnName = "id", nullable = false)
    private TestRun testRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_case_id", referencedColumnName = "id", nullable = false)
    private TestCase testCase;


    @OneToOne(mappedBy = "testRunItem", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private TestResult testResult;
}
