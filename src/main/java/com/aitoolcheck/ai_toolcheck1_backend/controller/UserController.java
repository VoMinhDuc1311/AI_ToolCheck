package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.user.req.AdminResetPasswordRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.user.req.CreateUserRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.user.req.UpdateUserRoleRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.user.req.UpdateUserStatusRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.user.res.UserResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.UserService;
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
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    @PostMapping
    public ResponseEntity<ApiResponse<UserResponse>> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserResponse response = userService.createUser(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<UserResponse>builder()
                .code("SUCCESS")
                .message("User created successfully.")
                .data(response)
                .timestamp(LocalDateTime.now())
                .build());
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<UserResponse>>> getUsers() {
        List<UserResponse> response = userService.getUsers();

        return ResponseEntity.ok(ApiResponse.<List<UserResponse>>builder()
                .code("SUCCESS")
                .message("Users fetched successfully.")
                .data(response)
                .timestamp(LocalDateTime.now())
                .build());
    }

    @GetMapping("/{id}")
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
