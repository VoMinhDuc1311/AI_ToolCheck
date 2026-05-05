package com.aitoolcheck.ai_toolcheck1_backend.dto.apidocument.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.DocumentType;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor

public class UpdateApiDocumentRequest {
    private String documentName;

    private DocumentType documentType;

    @Min(value = 1, message = "currentVersionNo must be greater than or equal to 1")
    private Integer currentVersionNo;
}
