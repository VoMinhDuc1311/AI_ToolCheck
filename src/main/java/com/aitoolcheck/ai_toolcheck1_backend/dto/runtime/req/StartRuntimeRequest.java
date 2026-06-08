package com.aitoolcheck.ai_toolcheck1_backend.dto.runtime.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.BuildStrategy;
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
public class StartRuntimeRequest {
    private BuildStrategy buildStrategy;
    public BuildStrategy effectiveStrategy() {
        return buildStrategy != null ? buildStrategy : BuildStrategy.AUTO;
    }
}
