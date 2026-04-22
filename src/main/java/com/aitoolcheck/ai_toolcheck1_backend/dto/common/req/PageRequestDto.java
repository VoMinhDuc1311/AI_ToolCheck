package com.aitoolcheck.ai_toolcheck1_backend.dto.common.req;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
