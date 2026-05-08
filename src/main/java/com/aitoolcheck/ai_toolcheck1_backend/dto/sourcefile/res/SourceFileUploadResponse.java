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
    private UUID uploadVersionId;
    private Integer versionNo;
    private int totalJavaFilesFound;
    private int savedFiles;
    private int ignoredFiles;
    private int addedFiles;
    private int updatedFiles;
    private int unchangedFiles;
    private int deletedFiles;
    private String status;
    private String summary;
}
