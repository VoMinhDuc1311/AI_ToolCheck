package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.req.CreateAiJobLogRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.req.TriggerAiJobRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res.AiJobLogResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@RequestMapping("/v1/ai-job-logs")
@RequiredArgsConstructor
@Tag(name = "AI Job Logs", description = "AI job creation, triggering, lookup, and statistics APIs")
public class AiJobLogController {

    private final AiJobLogService aiJobLogService;

    @PostMapping("/trigger")
    @Operation(
            summary = "Trigger AI job",
            description = "Create a pending AI job and send it to the AI processing queue.",
            operationId = "triggerAiJob"
    )
    public ResponseEntity<ApiResponse<AiJobLogResponse>> triggerAiJob(@Valid @RequestBody TriggerAiJobRequest request) {
        log.info("Đã nhận yêu cầu Trigger AI Job với Skill Code: {}", request.getSkillCode());
        
        AiJobLogResponse savedJobDto = aiJobLogService.createPendingJobAndTriggerAi(
                request.getPromptText(),
                request.getSkillCode(),
                request.getProjectId(),
                request.getSourceFileId(),
                request.getApiEndpointId()
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
     * Endpoint tự động trigger phân tích/làm giàu cho toàn bộ API Endpoints của một dự án.
     */
    @PostMapping("/trigger/project/{projectId}/enrich-endpoints")
    public ResponseEntity<ApiResponse<String>> triggerEnrichmentForProject(@PathVariable UUID projectId) {
        log.info("Đã nhận yêu cầu tự động làm giàu toàn bộ API Endpoints cho Project ID: {}", projectId);
        
        int triggeredCount = aiJobLogService.triggerEnrichmentForProject(projectId);
        
        ApiResponse<String> response = ApiResponse.<String>builder()
                .code(String.valueOf(HttpStatus.ACCEPTED.value()))
                .message("Đã kích hoạt thành công " + triggeredCount + " jobs làm giàu tài liệu.")
                .data("Đã đẩy " + triggeredCount + " messages vào RabbitMQ.")
                .timestamp(LocalDateTime.now())
                .build();
                
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * Endpoint tạo một AI Job Log ở trạng thái PENDING.
     */
    @PostMapping
    @Operation(
            summary = "Create pending AI job",
            description = "Create an AI job log in PENDING status.",
            operationId = "createPendingAiJob"
    )
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
    @Operation(
            summary = "Get AI job by id",
            description = "Get AI job log detail by UUID.",
            operationId = "getAiJobById"
    )
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

    /**
     * Endpoint lấy thống kê tổng hợp của AI Job Logs.
     */
    @GetMapping("/statistics")
    @Operation(
            summary = "Get AI job statistics",
            description = "Get aggregate statistics for AI job logs.",
            operationId = "getAiJobStatistics"
    )
    public ResponseEntity<ApiResponse<com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res.AiJobStatisticResponse>> getJobStatistics() {
        log.info("Nhận yêu cầu lấy thống kê AiJobLog");
        
        com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res.AiJobStatisticResponse stats = aiJobLogService.getJobStatistics();
        
        ApiResponse<com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res.AiJobStatisticResponse> response = ApiResponse.<com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.res.AiJobStatisticResponse>builder()
                .code("200")
                .message("Success")
                .data(stats)
                .timestamp(LocalDateTime.now())
                .build();
                
        return ResponseEntity.ok(response);
    }
}
