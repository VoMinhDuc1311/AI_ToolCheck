package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aijoblog.req.TriggerAiJobRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;
import com.aitoolcheck.ai_toolcheck1_backend.service.AiJobLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@Slf4j
@RestController
@RequestMapping("/api/v1/ai-jobs")
@RequiredArgsConstructor
public class AiJobLogController {

    private final AiJobLogService aiJobLogService;

    /**
     * Endpoint tiếp nhận yêu cầu phân tích/sinh mã từ AI (Fire-and-Forget).
     * Yêu cầu sẽ được lưu trạng thái PENDING và đẩy vào hàng đợi (RabbitMQ) để xử lý ngầm.
     * Phương thức này trả về ngay lập tức (non-blocking) để không làm treo giao diện người dùng.
     *
     * @param request DTO chứa thông tin promptText và skillCode cần xử lý
     * @return ResponseEntity với mã trạng thái 202 ACCEPTED xác nhận đã tiếp nhận yêu cầu
     */
    @PostMapping("/trigger")
    public ResponseEntity<ApiResponse<AiJobLog>> triggerAiJob(@Valid @RequestBody TriggerAiJobRequest request) {
        log.info("Đã nhận yêu cầu Trigger AI Job với Skill Code: {}", request.getSkillCode());
        
        // Gọi service tạo Job PENDING và ném vào queue
        AiJobLog savedJob = aiJobLogService.createPendingJobAndTriggerAi(
                request.getPromptText(),
                request.getSkillCode(),
                request.getProjectId(),
                request.getSourceFileId()
        );
        
        log.info("Đã xử lý Trigger AI Job thành công. Job ID: {}", savedJob.getId());
        
        // Đóng gói Response
        ApiResponse<AiJobLog> response = ApiResponse.<AiJobLog>builder()
                .code(String.valueOf(HttpStatus.ACCEPTED.value()))
                .message("Yêu cầu đã được hệ thống tiếp nhận và đang được xử lý ngầm.")
                .data(savedJob)
                .timestamp(LocalDateTime.now())
                .build();
                
        // Trả về HTTP Status 202 (Accepted)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
