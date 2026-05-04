package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.req.CreateAiJobLogRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.req.TriggerAiJobRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res.AiJobLogResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/ai-job-logs")
@RequiredArgsConstructor
public class AiJobLogController {

    private final AiJobLogService aiJobLogService;

    /**
     * Endpoint tiếp nhận yêu cầu phân tích/sinh mã từ AI (Fire-and-Forget).
     * Yêu cầu sẽ được lưu trạng thái PENDING và đẩy vào hàng đợi (RabbitMQ) để xử lý ngầm.
     */
    @PostMapping("/trigger")
    public ResponseEntity<ApiResponse<AiJobLogResponse>> triggerAiJob(@Valid @RequestBody TriggerAiJobRequest request) {
        log.info("Đã nhận yêu cầu Trigger AI Job với Skill Code: {}", request.getSkillCode());
        
        AiJobLogResponse savedJobDto = aiJobLogService.createPendingJobAndTriggerAi(
                request.getPromptText(),
                request.getSkillCode(),
                request.getProjectId(),
                request.getSourceFileId()
        );
        
        log.info("Đã xử lý Trigger AI Job thành công. Job ID: {}", savedJobDto.getId());
        
        ApiResponse<AiJobLogResponse> response = ApiResponse.<AiJobLogResponse>builder()
                .code(String.valueOf(HttpStatus.ACCEPTED.value()))
                .message("Yêu cầu đã được hệ thống tiếp nhận và đang được xử lý ngầm.")
                .data(savedJobDto)
                .timestamp(LocalDateTime.now())
                .build();
                
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * Endpoint tạo một AI Job Log ở trạng thái PENDING.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<AiJobLogResponse>> createPendingJob(@Valid @RequestBody CreateAiJobLogRequest request) {
        log.info("Nhận yêu cầu tạo AiJobLog PENDING: {}", request.getJobType());
        
        AiJobLogResponse responseDto = aiJobLogService.createPendingJob(request);
        
        ApiResponse<AiJobLogResponse> response = ApiResponse.<AiJobLogResponse>builder()
                .code("201")
                .message("Created pending job successfully")
                .data(responseDto)
                .timestamp(LocalDateTime.now())
                .build();
                
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Endpoint lấy chi tiết trạng thái của AI Job Log.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AiJobLogResponse>> getJobById(@PathVariable UUID id) {
        log.info("Nhận yêu cầu lấy chi tiết AiJobLog ID: {}", id);
        
        AiJobLogResponse responseDto = aiJobLogService.getJobById(id);
        
        ApiResponse<AiJobLogResponse> response = ApiResponse.<AiJobLogResponse>builder()
                .code("200")
                .message("Success")
                .data(responseDto)
                .timestamp(LocalDateTime.now())
                .build();
                
        return ResponseEntity.ok(response);
    }
}
