package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.user.req.AdminResetPasswordRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.user.req.CreateUserRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.user.req.UpdateUserRoleRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.user.req.UpdateUserStatusRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.user.res.UserResponse;

import java.util.List;
import java.util.UUID;

public interface UserService {

    UserResponse createUser(CreateUserRequest request);

    List<UserResponse> getUsers();

    UserResponse getUserById(UUID id);

    UserResponse updateRole(UUID id, UpdateUserRoleRequest request);

    UserResponse updateStatus(UUID id, UpdateUserStatusRequest request);

    UserResponse resetPassword(UUID id, AdminResetPasswordRequest request);
}
