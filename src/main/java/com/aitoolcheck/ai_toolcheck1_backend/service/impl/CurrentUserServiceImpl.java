package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.enums.UserRole;
import com.aitoolcheck.ai_toolcheck1_backend.exception.UnauthorizedException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AppUserRepository;
import com.aitoolcheck.ai_toolcheck1_backend.security.CustomUserDetails;
import com.aitoolcheck.ai_toolcheck1_backend.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CurrentUserServiceImpl implements CurrentUserService {

    private final AppUserRepository appUserRepository;

    @Override
    @Transactional(readOnly = true)
    public AppUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException("Authentication is required");
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomUserDetails userDetails) {
            UUID userId = userDetails.getUser().getId();
            return appUserRepository.findById(userId)
                    .orElseThrow(() -> new UnauthorizedException("Authentication is required"));
        }

        String email = normalizeEmail(authentication.getName());
        if (email == null) {
            throw new UnauthorizedException("Authentication is required");
        }

        return appUserRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException("Authentication is required"));
    }

    @Override
    @Transactional(readOnly = true)
    public UUID getCurrentUserId() {
        return getCurrentUser().getId();
    }

    @Override
    @Transactional(readOnly = true)
    public String getCurrentUserEmail() {
        return getCurrentUser().getEmail();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAdmin() {
        return hasRole(UserRole.ADMIN);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasRole(UserRole role) {
        return getCurrentUser().getRole() == role;
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
