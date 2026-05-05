package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.AuthProperties;
import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.req.ChangePasswordRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.req.LoginRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.req.LogoutRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.req.RefreshTokenRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.res.AuthMeResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.res.AuthTokenResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.res.AuthUserResponse;
import com.aitoolcheck.ai_toolcheck1_backend.enums.UserStatus;
import com.aitoolcheck.ai_toolcheck1_backend.exception.ForbiddenException;
import com.aitoolcheck.ai_toolcheck1_backend.exception.UnauthorizedException;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import com.aitoolcheck.ai_toolcheck1_backend.model.RefreshToken;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AppUserRepository;
import com.aitoolcheck.ai_toolcheck1_backend.repository.RefreshTokenRepository;
import com.aitoolcheck.ai_toolcheck1_backend.security.CustomUserDetails;
import com.aitoolcheck.ai_toolcheck1_backend.security.JwtService;
import com.aitoolcheck.ai_toolcheck1_backend.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthServiceImpl implements AuthService {

    private static final String TOKEN_TYPE = "Bearer";

    private final AppUserRepository appUserRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthProperties authProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public AuthTokenResponse login(LoginRequest request) {
        String email = normalizeEmail(request.getEmail());
        AppUser user = appUserRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        ensureActiveUser(user);

        user.setLastLoginAt(LocalDateTime.now());
        AppUser savedUser = appUserRepository.save(user);

        String accessToken = jwtService.generateAccessToken(savedUser);
        String rawRefreshToken = createRefreshToken(savedUser).rawToken();

        return buildTokenResponse(accessToken, rawRefreshToken, savedUser);
    }

    @Override
    public AuthTokenResponse refresh(RefreshTokenRequest request) {
        String rawRefreshToken = request.getRefreshToken();
        RefreshToken oldToken = refreshTokenRepository.findByTokenHash(hashRefreshToken(rawRefreshToken))
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

        if (Boolean.TRUE.equals(oldToken.getRevoked())) {
            throw new UnauthorizedException("Invalid refresh token");
        }

        LocalDateTime now = LocalDateTime.now();
        if (oldToken.getExpiresAt().isBefore(now)) {
            oldToken.setRevoked(true);
            oldToken.setRevokedAt(now);
            refreshTokenRepository.save(oldToken);
            throw new UnauthorizedException("Refresh token expired");
        }

        AppUser user = oldToken.getUser();
        ensureActiveUser(user);

        oldToken.setRevoked(true);
        oldToken.setRevokedAt(now);

        CreatedRefreshToken createdRefreshToken = createRefreshToken(user);
        oldToken.setReplacedByTokenId(createdRefreshToken.entity().getId());
        refreshTokenRepository.save(oldToken);

        String accessToken = jwtService.generateAccessToken(user);
        return buildTokenResponse(accessToken, createdRefreshToken.rawToken(), user);
    }

    @Override
    public void logout(LogoutRequest request) {
        refreshTokenRepository.findByTokenHash(hashRefreshToken(request.getRefreshToken()))
                .ifPresent(token -> {
                    if (!Boolean.TRUE.equals(token.getRevoked())) {
                        token.setRevoked(true);
                        token.setRevokedAt(LocalDateTime.now());
                        refreshTokenRepository.save(token);
                    }
                });
    }

    @Override
    @Transactional(readOnly = true)
    public AuthMeResponse getMe() {
        return toAuthMeResponse(getCurrentUserEntity());
    }

    @Override
    public void changePassword(ChangePasswordRequest request) {
        AppUser user = getCurrentUserEntity();

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        AppUser savedUser = appUserRepository.save(user);
        revokeActiveRefreshTokens(savedUser.getId());
    }

    private CreatedRefreshToken createRefreshToken(AppUser user) {
        String rawToken;
        String tokenHash;

        do {
            rawToken = generateRawRefreshToken();
            tokenHash = hashRefreshToken(rawToken);
        } while (refreshTokenRepository.existsByTokenHash(tokenHash));

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .revoked(false)
                .expiresAt(LocalDateTime.now().plusDays(authProperties.getRefreshTokenDays()))
                .build();

        RefreshToken savedToken = refreshTokenRepository.save(refreshToken);
        return new CreatedRefreshToken(rawToken, savedToken);
    }

    private AppUser getCurrentUserEntity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof CustomUserDetails userDetails)) {
            throw new UnauthorizedException("Authentication is required");
        }

        UUID userId = userDetails.getUser().getId();
        return appUserRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Authentication is required"));
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

    private void ensureActiveUser(AppUser user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("User account is not active");
        }
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String generateRawRefreshToken() {
        byte[] bytes = new byte[authProperties.getRefreshTokenBytes()];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashRefreshToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }

    private AuthTokenResponse buildTokenResponse(String accessToken, String rawRefreshToken, AppUser user) {
        return AuthTokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken)
                .tokenType(TOKEN_TYPE)
                .expiresIn(authProperties.getAccessTokenMinutes() * 60)
                .user(toAuthUserResponse(user))
                .build();
    }

    private AuthUserResponse toAuthUserResponse(AppUser user) {
        return AuthUserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole())
                .status(user.getStatus())
                .build();
    }

    private AuthMeResponse toAuthMeResponse(AppUser user) {
        return AuthMeResponse.builder()
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

    private record CreatedRefreshToken(String rawToken, RefreshToken entity) {
    }
}
