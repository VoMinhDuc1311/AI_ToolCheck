package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res.SourceFileDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res.SourceFileResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res.SourceFileUploadResponse;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

public interface SourceFileService {
    SourceFileUploadResponse uploadZip(UUID projectId, MultipartFile file);

    SourceFileUploadResponse importZip(UUID projectId, InputStream zipInputStream, String originalFilename,
                                       boolean stripSingleRootDirectory);

    List<SourceFileResponse> getByProjectId(UUID projectId);

    SourceFileDetailResponse getById(UUID id);

}
