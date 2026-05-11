package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.res.AuthMeResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.user.req.AdminResetPasswordRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.user.req.CreateUserRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.user.req.UpdateUserRoleRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.user.req.UpdateUserStatusRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.user.res.UserResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.AuthService;
import com.aitoolcheck.ai_toolcheck1_backend.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/users")
@Tag(name = "Users", description = "Admin user management APIs")
public class UserController {

    private final UserService userService;
    private final AuthService authService;

    @PostMapping
    @Operation(
            summary = "Create user",
            description = "Create a new ADMIN or MEMBER account. ADMIN only.",
            operationId = "createUser"
    )
    public ResponseEntity<ApiResponse<UserResponse>> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserResponse response = userService.create(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<UserResponse>builder()
                .code("SUCCESS")
                .message("User created successfully.")
                .data(response)
                .timestamp(LocalDateTime.now())
                .build());
    }

    @GetMapping
    @Operation(
            summary = "Get all users",
            description = "Get all user accounts. ADMIN only.",
            operationId = "getAllUsers"
    )
    public ResponseEntity<ApiResponse<List<UserResponse>>> getUsers() {
        List<UserResponse> response = userService.getUsers();

        return ResponseEntity.ok(ApiResponse.<List<UserResponse>>builder()
                .code("SUCCESS")
                .message("Users fetched successfully.")
                .data(response)
                .timestamp(LocalDateTime.now())
                .build());
    }

    @GetMapping("/me")
    @Operation(
            summary = "Get current user",
            description = "Get the current authenticated user profile.",
            operationId = "getCurrentUserFromUsers"
    )
    public ResponseEntity<ApiResponse<AuthMeResponse>> getMe() {
        AuthMeResponse response = authService.getMe();

        return ResponseEntity.ok(ApiResponse.<AuthMeResponse>builder()
                .code("SUCCESS")
                .message("Current user fetched successfully.")
                .data(response)
                .timestamp(LocalDateTime.now())
                .build());
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get user by id",
            description = "Get a user account by UUID. ADMIN only.",
            operationId = "getUserById"
    )
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable UUID id) {
        UserResponse response = userService.getUserById(id);

        return ResponseEntity.ok(ApiResponse.<UserResponse>builder()
                .code("SUCCESS")
                .message("User fetched successfully.")
                .data(response)
                .timestamp(LocalDateTime.now())
                .build());
    }

    @PatchMapping("/{id}/role")
    @Operation(
            summary = "Update user role",
            description = "Update a user's role to ADMIN or MEMBER. ADMIN only.",
            operationId = "updateUserRole"
    )
    public ResponseEntity<ApiResponse<UserResponse>> updateRole(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRoleRequest request
    ) {
        UserResponse response = userService.updateRole(id, request);

        return ResponseEntity.ok(ApiResponse.<UserResponse>builder()
                .code("SUCCESS")
                .message("User role updated successfully.")
                .data(response)
                .timestamp(LocalDateTime.now())
                .build());
    }

    @PatchMapping("/{id}/status")
    @Operation(
            summary = "Update user status",
            description = "Update a user's status to ACTIVE, DISABLED, or LOCKED. ADMIN only.",
            operationId = "updateUserStatus"
    )
    public ResponseEntity<ApiResponse<UserResponse>> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserStatusRequest request
    ) {
        UserResponse response = userService.updateStatus(id, request);

        return ResponseEntity.ok(ApiResponse.<UserResponse>builder()
                .code("SUCCESS")
                .message("User status updated successfully.")
                .data(response)
                .timestamp(LocalDateTime.now())
                .build());
    }

    @PatchMapping("/{id}/reset-password")
    @Operation(
            summary = "Reset user password",
            description = "Reset a user's password. ADMIN only.",
            operationId = "resetUserPassword"
    )
    public ResponseEntity<ApiResponse<UserResponse>> resetPassword(
            @PathVariable UUID id,
            @Valid @RequestBody AdminResetPasswordRequest request
    ) {
        UserResponse response = userService.resetPassword(id, request);

        return ResponseEntity.ok(ApiResponse.<UserResponse>builder()
                .code("SUCCESS")
                .message("User password reset successfully.")
                .data(response)
                .timestamp(LocalDateTime.now())
                .build());
    }
}
