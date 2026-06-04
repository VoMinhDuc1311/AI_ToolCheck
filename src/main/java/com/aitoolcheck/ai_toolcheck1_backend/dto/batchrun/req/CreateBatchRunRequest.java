package com.aitoolcheck.ai_toolcheck1_backend.dto.batchrun.req;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateBatchRunRequest {

    @NotBlank(message = "name is required")
    @Size(max = 150, message = "name must not exceed 150 characters")
    private String name;

    @NotEmpty(message = "projectIds must not be empty")
    private List<UUID> projectIds;

    @Valid
    private BatchRunOptionsRequest options;
}
