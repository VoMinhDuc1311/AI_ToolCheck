package com.aitoolcheck.ai_toolcheck1_backend.dto.user.req;

import com.aitoolcheck.ai_toolcheck1_backend.enums.UserRole;
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
public class UpdateUserRoleRequest {

    @NotNull(message = "role is required")
    private UserRole role;
}
