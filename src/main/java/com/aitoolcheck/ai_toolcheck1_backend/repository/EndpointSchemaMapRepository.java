package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.model.EndpointSchemaMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EndpointSchemaMapRepository extends JpaRepository<EndpointSchemaMap, UUID> {

    List<EndpointSchemaMap> findByApiEndpointId(UUID apiEndpointId);

    void deleteByApiEndpointId(UUID apiEndpointId);

    void deleteByApiEndpoint_SourceProject_Id(UUID projectId);
}
