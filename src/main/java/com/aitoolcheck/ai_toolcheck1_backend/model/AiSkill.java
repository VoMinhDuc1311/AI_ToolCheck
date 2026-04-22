package com.aitoolcheck.ai_toolcheck1_backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "ai_skill")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiSkill {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "skill_code")
    private String skillCode;

    @Column(name = "skill_name")
    private String skillName;

    @Column(name = "description")
    private String description;



    @OneToMany(mappedBy = "aiSkill", fetch = FetchType.LAZY)
    private List<AiJobLog> aiJobLogs;
}
