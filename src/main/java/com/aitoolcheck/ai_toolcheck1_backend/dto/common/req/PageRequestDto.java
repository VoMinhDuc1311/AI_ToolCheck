package com.aitoolcheck.ai_toolcheck1_backend.dto.common.req;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageRequestDto {
    private int page;
    private int pageSize;
    private String sortBy;
    private String sortDir;
}
