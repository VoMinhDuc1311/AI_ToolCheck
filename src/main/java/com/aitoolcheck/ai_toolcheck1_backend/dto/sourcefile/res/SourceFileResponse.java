package com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res;
import com.aitoolcheck.ai_toolcheck1_backend.enums.FileType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


import java.time.LocalDateTime;
import java.util.UUID;
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor

public class SourceFileResponse {
    private UUID id;
    private UUID projectId;
    private String fileName;
    private String filePath;
    private FileType fileType;
    private String checksumSha256;
    private Boolean parsedFlag;
    private String parseError;
    private Boolean activeFlag;
    private Boolean deletedFlag;
    private UUID uploadVersionId;
    private UUID lastSeenUploadVersionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
