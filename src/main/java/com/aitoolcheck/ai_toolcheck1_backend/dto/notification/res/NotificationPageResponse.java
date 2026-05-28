package com.aitoolcheck.ai_toolcheck1_backend.dto.notification.res;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Pagination wrapper for notification list responses.
 */
@Getter
@Builder
public class NotificationPageResponse {

    private List<NotificationResponse> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean last;
}
