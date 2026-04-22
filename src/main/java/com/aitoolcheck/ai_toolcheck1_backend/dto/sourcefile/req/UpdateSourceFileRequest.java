package com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.FileType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateSourceFileRequest {

    private String filePath;
    private String fileName;
    private String packageName;
    private String className;
    private FileType fileType;
    private Boolean parsedFlag;
}