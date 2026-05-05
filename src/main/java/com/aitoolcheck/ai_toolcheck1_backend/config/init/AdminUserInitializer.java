package com.aitoolcheck.ai_toolcheck1_backend.config.init;

import com.aitoolcheck.ai_toolcheck1_backend.config.properties.AuthProperties;
import com.aitoolcheck.ai_toolcheck1_backend.enums.UserRole;
import com.aitoolcheck.ai_toolcheck1_backend.enums.UserStatus;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import com.aitoolcheck.ai_toolcheck1_backend.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminUserInitializer implements CommandLineRunner {

    private final AuthProperties authProperties;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (!Boolean.TRUE.equals(authProperties.getSeedAdmin().getEnabled())) {
            return;
        }

        if (appUserRepository.existsByRole(UserRole.ADMIN)) {
            return;
        }

        String email = normalizeEmail(authProperties.getSeedAdmin().getEmail());
        String password = authProperties.getSeedAdmin().getPassword();

        if (isBlank(email)) {
            throw new IllegalStateException("app.auth.seed-admin.email must be configured when seed admin is enabled");
        }
        if (isBlank(password)) {
            throw new IllegalStateException("app.auth.seed-admin.password must be configured when seed admin is enabled");
        }

        AppUser admin = AppUser.builder()
                .email(email)
                .fullName(normalizeNullableText(authProperties.getSeedAdmin().getFullName()))
                .passwordHash(passwordEncoder.encode(password))
                .role(UserRole.ADMIN)
                .status(UserStatus.ACTIVE)
                .build();

        appUserRepository.save(admin);
        log.info("Seed ADMIN user created: {}", email);
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
