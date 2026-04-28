package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.apimetadata.res.ApiMetadataParseResultResponse;

import java.util.UUID;

public interface ApiMetadataParserService {

    ApiMetadataParseResultResponse parseProject(UUID projectId);
}
