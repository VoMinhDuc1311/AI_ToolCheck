package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.enums.UserRole;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;

import java.util.UUID;

public interface CurrentUserService {
    AppUser getCurrentUser();
    UUID getCurrentUserId();
    String getCurrentUserEmail();
    boolean isAdmin();
    boolean hasRole(UserRole role);
}
