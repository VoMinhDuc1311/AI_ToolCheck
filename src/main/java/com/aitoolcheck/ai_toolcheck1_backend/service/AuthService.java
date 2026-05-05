package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.req.ChangePasswordRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.req.LoginRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.req.LogoutRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.req.RefreshTokenRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.res.AuthMeResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.auth.res.AuthTokenResponse;

public interface AuthService {

    AuthTokenResponse login(LoginRequest request);

    AuthTokenResponse refresh(RefreshTokenRequest request);

    void logout(LogoutRequest request);

    AuthMeResponse getMe();

    void changePassword(ChangePasswordRequest request);
}
