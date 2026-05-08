package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.user.req.AdminResetPasswordRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.user.req.CreateUserRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.user.req.UpdateUserRoleRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.user.req.UpdateUserStatusRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.user.res.UserResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.UserRole;
import com.aitoolcheck.ai_toolcheck1_backend.enums.UserStatus;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ResourceNotFoundException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import com.aitoolcheck.ai_toolcheck1_backend.model.RefreshToken;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AppUserRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.RefreshTokenRepository;
import com.aitoolcheck.ai_toolcheck1_backend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class UserServiceImpl implements UserService {

    private final AppUserRepository appUserRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public UserResponse create(CreateUserRequest request) {
        String email = normalizeEmail(request.getEmail());

        if (appUserRepository.existsByEmailIgnoreCase(email)) {
            throw new BadRequestException("Email already exists: " + email);
        }

        AppUser user = AppUser.builder()
                .email(email)
                .fullName(normalizeNullableText(request.getFullName()))
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole())
                .status(request.getStatus() == null ? UserStatus.ACTIVE : request.getStatus())
                .build();

        AppUser saved = appUserRepository.save(user);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> getUsers() {
        return appUserRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getUserById(UUID id) {
        AppUser user = getUserEntity(id);
        return toResponse(user);
    }

    @Override
    public UserResponse updateRole(UUID id, UpdateUserRoleRequest request) {
        AppUser user = getUserEntity(id);

        if (user.getRole() == UserRole.ADMIN && request.getRole() != UserRole.ADMIN) {
            ensureNotLastAdmin();
        }

        user.setRole(request.getRole());
        AppUser saved = appUserRepository.save(user);
        return toResponse(saved);
    }

    @Override
    public UserResponse updateStatus(UUID id, UpdateUserStatusRequest request) {
        AppUser user = getUserEntity(id);

        if (user.getRole() == UserRole.ADMIN && request.getStatus() != UserStatus.ACTIVE) {
            ensureNotLastAdmin();
        }

        user.setStatus(request.getStatus());
        AppUser saved = appUserRepository.save(user);
        return toResponse(saved);
    }

    @Override
    public UserResponse resetPassword(UUID id, AdminResetPasswordRequest request) {
        AppUser user = getUserEntity(id);

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        AppUser saved = appUserRepository.save(user);

        revokeActiveRefreshTokens(saved.getId());

        return toResponse(saved);
    }

    private AppUser getUserEntity(UUID id) {
        return appUserRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AppUser not found with id: " + id));
    }

    private void ensureNotLastAdmin() {
        long adminCount = appUserRepository.countByRole(UserRole.ADMIN);

        if (adminCount <= 1) {
            throw new BadRequestException("Cannot remove or disable the last ADMIN user.");
        }
    }

    private void revokeActiveRefreshTokens(UUID userId) {
        List<RefreshToken> activeTokens = refreshTokenRepository.findByUserIdAndRevokedFalse(userId);
        LocalDateTime now = LocalDateTime.now();

        for (RefreshToken token : activeTokens) {
            token.setRevoked(true);
            token.setRevokedAt(now);
        }

        refreshTokenRepository.saveAll(activeTokens);
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }

        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private UserResponse toResponse(AppUser user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole())
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
