package com.aitoolcheck.ai_toolcheck1_backend.repository;



import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface SourceProjectRepository extends JpaRepository<SourceProject, UUID> {

    boolean existsByProjectKey(String projectKey);

    boolean existsByProjectName(String projectName);

    boolean existsByProjectNameAndIdNot(String projectName, UUID id);
}