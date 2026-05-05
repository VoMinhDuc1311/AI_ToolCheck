package com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.DocumentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateApiDocumentRequest {
    @NotNull(message = "projectId is required")
    private UUID projectId;

    private String documentName;

    private DocumentType documentType;
}
