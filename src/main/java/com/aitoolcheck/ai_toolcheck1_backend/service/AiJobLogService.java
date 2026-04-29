package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.model.AiJobLog;

import java.util.UUID;

public interface AiJobLogService {

    /**
     * Khởi tạo một Job với trạng thái PENDING và đẩy thông tin (message) vào RabbitMQ queue.
     * Hàm này được thiết kế để xử lý cực nhanh (non-blocking) nhằm trả về phản hồi cho người dùng ngay lập tức,
     * quá trình xử lý AI thực tế sẽ được worker lấy từ queue ra và thực thi ngầm.
     *
     * @param promptText Câu lệnh (prompt) hoặc dữ liệu đầu vào cho AI
     * @param skillCode Mã kỹ năng (skill code) cần sử dụng
     * @return Đối tượng AiJobLog đã được lưu trong database với trạng thái PENDING
     */
    AiJobLog createPendingJobAndTriggerAi(String promptText, String skillCode,
                                           UUID projectId, UUID sourceFileId);
}
