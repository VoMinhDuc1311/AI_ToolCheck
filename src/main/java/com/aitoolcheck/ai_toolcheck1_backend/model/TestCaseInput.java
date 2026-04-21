package com.aitoolcheck.ai_toolcheck1_backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "test_case_input")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestCaseInput {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "input_data", columnDefinition = "TEXT")
    private String inputData;

    // ── Relationships ──────────────────────────────────────────────────────────

    // Owning side of the 1-1: holds the UNIQUE FK test_case_id
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_case_id", referencedColumnName = "id", unique = true, nullable = false)
    private TestCase testCase;
}
