package com.aitoolcheck.ai_toolcheck1_backend.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    private String jwtSecret;

    private Long accessTokenMinutes = 15L;

    private Long refreshTokenDays = 14L;

    private Integer refreshTokenBytes = 64;

    private SeedAdmin seedAdmin = new SeedAdmin();

    @Getter
    @Setter
    public static class SeedAdmin {
        private Boolean enabled = false;
        private String email;
        private String password;
        private String fullName = "System Admin";
    }
}