package com.aitoolcheck.ai_toolcheck1_backend.repository;



import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ProjectVisibility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SourceProjectRepository extends JpaRepository<SourceProject, UUID> {

    boolean existsByProjectKey(String projectKey);

    boolean existsByProjectName(String projectName);

    boolean existsByProjectNameAndIdNot(String projectName, UUID id);

    List<SourceProject> findAllByOrderByCreatedAtDesc();

    List<SourceProject> findByOwnerUser_IdOrderByCreatedAtDesc(UUID ownerUserId);

    List<SourceProject> findByVisibilityOrderByCreatedAtDesc(ProjectVisibility visibility);

    boolean existsByIdAndOwnerUser_Id(UUID projectId, UUID ownerUserId);
}
