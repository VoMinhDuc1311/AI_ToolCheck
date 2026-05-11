package com.aitoolcheck.ai_toolcheck1_backend.repository;

import com.aitoolcheck.ai_toolcheck1_backend.enums.UserRole;
import com.aitoolcheck.ai_toolcheck1_backend.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmail(String email);

    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByRole(UserRole role);

    long countByRole(UserRole role);
}
