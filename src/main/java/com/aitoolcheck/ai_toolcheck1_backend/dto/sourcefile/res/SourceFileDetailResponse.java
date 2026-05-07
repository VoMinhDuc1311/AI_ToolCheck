package com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res;
import com.aitoolcheck.ai_toolcheck1_backend.enums.FileType;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor

public class SourceFileDetailResponse {
    private UUID id;
    private UUID projectId;
    private String filePath;
    private String fileName;
    private String packageName;
    private String className;
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
