package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.CreateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.PermanentDeleteProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.UpdateProjectVisibilityRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.req.UpdateSourceProjectRequest;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceProjectDetailResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.res.SourceProjectResponse;

import java.util.List;
import java.util.UUID;

public interface SourceProjectService {

    SourceProjectDetailResponse create(CreateSourceProjectRequest request);

    SourceProjectDetailResponse getById(UUID id);

    /**
     * Return projects visible to the current user.
     *
     * @param includeArchived when {@code true}, archived projects are included; deleted are always excluded.
     */
    List<SourceProjectResponse> getAll(boolean includeArchived);

    /**
     * Return projects owned by or shared with the current user.
     *
     * @param includeArchived when {@code true}, archived projects are included.
     */
    List<SourceProjectResponse> getMine(boolean includeArchived);

    List<SourceProjectResponse> getPublic();

    /** Return archived projects visible to the current user (owner / ADMIN / member). */
    List<SourceProjectResponse> getArchived();

    SourceProjectDetailResponse update(UUID id, UpdateSourceProjectRequest request);

    SourceProjectDetailResponse updateVisibility(UUID id, UpdateProjectVisibilityRequest request);

    /**
     * Backward-compatible delete — now delegates to {@link #archiveProject(UUID)}.
     * Does NOT hard-delete. Fixes previous FK-violation bug.
     */
    void delete(UUID id);

    /**
     * Archive a project: hides it from default lists, preserves all child data.
     * Permission: OWNER or ADMIN only.
     */
    SourceProjectDetailResponse archiveProject(UUID id);

    /**
     * Restore an archived project: makes it visible in default lists again.
     * Permission: OWNER or ADMIN only.
     */
    SourceProjectDetailResponse restoreProject(UUID id);

    /**
     * Permanently and irreversibly delete a project and ALL its child data.
     * <ul>
     *   <li>Permission: OWNER or ADMIN only.</li>
     *   <li>Requires {@code request.confirmProjectKey} to exactly match the project's key.</li>
     *   <li>Runs in a single transaction; follows FK-safe deletion order.</li>
     * </ul>
     */
    void permanentlyDeleteProject(UUID id, PermanentDeleteProjectRequest request);
}
