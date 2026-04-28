package com.aitoolcheck.ai_toolcheck1_backend.service.rabbitmq;

import com.aitoolcheck.ai_toolcheck1_backend.config.RabbitMQConfig;
import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiInferenceResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.dto.rabbitmq.AiTaskMessage;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ExecutionStatus;
import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ParamIn;
import com.aitoolcheck.ai_toolcheck1_backend.enums.ResultStatus;
import com.aitoolcheck.ai_toolcheck1_backend.model.*;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AiJobLogRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.ApiEndpointRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.LegacyInferenceLogRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.GeminiApiClientService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiTaskConsumer {

    private final GeminiApiClientService geminiApiClientService;
    private final AiJobLogRepository aiJobLogRepository;
    private final LegacyInferenceLogRepository legacyInferenceLogRepository;
    private final ApiEndpointRepository apiEndpointRepository; // Bổ sung Repository này
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_NAME)
    @Transactional // Rất quan trọng để lưu cascade nhiều bảng (Log, Endpoint, Parameter)
    public void receiveAiTask(AiTaskMessage message) {
        log.info("Consumer: Received AI Task Message - JobId: {}, SkillCode: {}",
                message.getJobId(), message.getSkillCode());

        AiJobLog jobLog = null;
        try {
            // 1. Lấy lại AiJobLog từ DB
            UUID jobId = UUID.fromString(message.getJobId());
            jobLog = aiJobLogRepository.findById(jobId)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy JobLog với ID: " + jobId));

            // Set trạng thái RUNNING
            jobLog.setExecutionStatus(ExecutionStatus.RUNNING);
            aiJobLogRepository.save(jobLog);

            // Lấy SourceProject từ JobLog (Giải quyết vấn đề nullable = false)
            SourceProject currentProject = jobLog.getSourceProject();

            // 2. Routing logic theo SkillCode
            if ("SKILL_0".equalsIgnoreCase(message.getSkillCode())) {

                log.info("=> Executing AI Skill 0 (Legacy Extractor)...");
                String sourceCode = message.getPromptText();

                // 2.1. Gọi API AI
                AiInferenceResultDto resultDto = geminiApiClientService.extractLegacyApi(sourceCode);

                // 2.2. LƯU LOG THÔ: LegacyInferenceLog
                LegacyInferenceLog legacyLog = new LegacyInferenceLog();
                legacyLog.setSourceProject(currentProject);
                // legacyLog.setSourceFile(jobLog.getSourceFile()); // Nếu AiJobLog có mapping
                // tới SourceFile thì lấy ra set vào đây
                legacyLog.setInferredMetadataJson(objectMapper.writeValueAsString(resultDto));

                // Mặc định cho confidenceScore nếu AI không trả về
                if (resultDto.getEndpoints() != null && !resultDto.getEndpoints().isEmpty()
                        && resultDto.getEndpoints().get(0).getConfidence() != null) {
                    legacyLog.setConfidenceScore((int) (resultDto.getEndpoints().get(0).getConfidence() * 100));
                } else {
                    legacyLog.setConfidenceScore(80); // Tạm set 80% nếu thiếu
                }
                legacyInferenceLogRepository.save(legacyLog);

                // 2.3. MAPPING DỮ LIỆU: Bóc tách DTO lưu vào ApiEndpoint & ApiParameter
                if (resultDto.getEndpoints() != null && !resultDto.getEndpoints().isEmpty()) {
                    for (AiInferenceResultDto.EndpointDto epDto : resultDto.getEndpoints()) {

                        // Map sang ApiEndpoint
                        ApiEndpoint endpoint = new ApiEndpoint();
                        endpoint.setSourceProject(currentProject);
                        endpoint.setEndpointPath(epDto.getPath());

                        // Parse an toàn HttpMethod từ chuỗi AI trả về
                        try {
                            endpoint.setHttpMethod(HttpMethod.valueOf(epDto.getHttpMethod().toUpperCase()));
                        } catch (IllegalArgumentException | NullPointerException e) {
                            endpoint.setHttpMethod(HttpMethod.GET); // Default fallback
                        }

                        if (epDto.getSource() != null) {
                            endpoint.setControllerName(epDto.getSource().getClassName());
                            endpoint.setMethodName(epDto.getSource().getMethodName());
                        }

                        endpoint.setCreatedAt(LocalDateTime.now());

                        // Khởi tạo danh sách tham số
                        List<ApiParameter> parameters = new ArrayList<>();
                        if (epDto.getParameters() != null) {
                            for (AiInferenceResultDto.ParameterDto paramDto : epDto.getParameters()) {
                                ApiParameter parameter = new ApiParameter();
                                parameter.setApiEndpoint(endpoint); // Set reference ngược lại
                                parameter.setParamName(paramDto.getName());
                                parameter.setDataType(paramDto.getType() != null ? paramDto.getType() : "String");
                                parameter.setRequiredFlag(
                                        paramDto.getRequired() != null ? paramDto.getRequired() : false);
                                parameter.setExampleValue(paramDto.getExample());

                                try {
                                    parameter.setParamIn(ParamIn.valueOf(paramDto.getIn().toUpperCase()));
                                } catch (IllegalArgumentException | NullPointerException e) {
                                    parameter.setParamIn(ParamIn.QUERY); // Default
                                }
                                parameters.add(parameter);
                            }
                        }

                        // Lưu cascade cả Endpoint và Parameter (Vì ở Model ApiEndpoint bạn đã có
                        // cascade = CascadeType.ALL)
                        endpoint.setApiParameters(parameters);
                        apiEndpointRepository.save(endpoint);
                    }
                }

                // 2.4 Tracking Token (Mô phỏng lưu trữ, bạn có thể bóc số lượng token thật từ
                // GeminiResponse sau)
                jobLog.setTokenInput(sourceCode.length() / 4); // Ước tính 1 token ~ 4 ký tự
                jobLog.setTokenOutput(legacyLog.getInferredMetadataJson().length() / 4);

            } else {
                log.info("=> Calling Standard Gemini API (Mono)...");
                geminiApiClientService.sendPrompt(message.getPromptText()).block();
            }

            // 3. Cập nhật status thành SUCCESS
            log.info("=> Gemini API Call SUCCESS. Saving status for JobId: {}", message.getJobId());
            jobLog.setExecutionStatus(ExecutionStatus.SUCCESS);
            jobLog.setCompletedAt(LocalDateTime.now());
            aiJobLogRepository.save(jobLog);

        } catch (Exception e) {
            log.error("=> Error occurred while processing AI Task for JobId: {}. Exception: {}",
                    message.getJobId(), e.getMessage(), e);

            if (jobLog != null) {
                try {
                    jobLog.setExecutionStatus(ExecutionStatus.FAILED);
                    jobLog.setErrorMessage(e.getMessage());
                    jobLog.setCompletedAt(LocalDateTime.now());
                    aiJobLogRepository.save(jobLog);

                    log.info("=> Saved FAILED status to DB for JobId: {}", jobLog.getId());
                } catch (Exception dbEx) {
                    log.error("=> Failed to save FAILED status to DB for JobId: {}", jobLog.getId(), dbEx);
                }
            }
        }
    }
}