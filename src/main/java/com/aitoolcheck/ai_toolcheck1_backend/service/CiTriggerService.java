package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.ci.req.CiTriggerRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.ci.res.CiTriggerResponse;


public interface CiTriggerService {
    CiTriggerResponse trigger(CiTriggerRequest request);
}
