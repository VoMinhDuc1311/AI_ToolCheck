package com.aitoolcheck.ai_toolcheck1_backend.controller;

import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.req.ChangePasswordRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.req.LoginRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.req.LogoutRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.req.RefreshTokenRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.res.AuthMeResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.res.AuthTokenResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.ApiResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.common.res.MessageResponse;
import com.aitoolcheck.ai_toolcheck1_backend.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthTokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthTokenResponse response = authService.login(request);
        return ResponseEntity.ok(success("Login successful.", response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthTokenResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        AuthTokenResponse response = authService.refresh(request);
        return ResponseEntity.ok(success("Token refreshed successfully.", response));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<MessageResponse>> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request);
        return ResponseEntity.ok(success(
                "Logout successful.",
                MessageResponse.builder().message("Logout successful.").build()
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AuthMeResponse>> getMe() {
        AuthMeResponse response = authService.getMe();
        return ResponseEntity.ok(success("Current user fetched successfully.", response));
    }

    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<MessageResponse>> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return ResponseEntity.ok(success(
                "Password changed successfully.",
                MessageResponse.builder().message("Password changed successfully.").build()
        ));
    }

    private <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .code("SUCCESS")
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
