package com.aitoolcheck.ai_toolcheck1_backend.dto.user.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.UserStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUserStatusRequest {

    @NotNull(message = "status is required")
    private UserStatus status;
}
