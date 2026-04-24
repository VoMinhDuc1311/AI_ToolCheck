package com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor

public class SourceFileUploadResponse {
    private UUID projectId;
    private String projectName;
    private int totalJavaFilesFound;
    private int savedFiles;
    private int ignoredFiles;
}
