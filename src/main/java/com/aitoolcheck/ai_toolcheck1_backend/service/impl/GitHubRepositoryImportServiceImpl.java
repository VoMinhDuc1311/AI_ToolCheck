package com.aitoolcheck.ai_toolcheck1_backend.service.impl;

import com.aitoolcheck.ai_toolcheck1_backend.dto.sourcefile.res.SourceFileUploadResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.github.GitHubRepositoryCheckResponse;
import com.aitoolcheck.ai_toolcheck1_backend.dto.sourceproject.github.GitHubRepositoryImportResponse;
import com.aitoolcheck.ai_toolcheck1_backend.exception.BadRequestException;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import com.aitoolcheck.ai_toolcheck1_backend.service.GitHubRepositoryCheckService;
import com.aitoolcheck.ai_toolcheck1_backend.service.GitHubRepositoryImportService;
import com.aitoolcheck.ai_toolcheck1_backend.service.SourceFileService;
import com.aitoolcheck.ai_toolcheck1_backend.service.access.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubRepositoryImportServiceImpl implements GitHubRepositoryImportService {
    private static final String USER_AGENT = "AI-ToolCheck-Backend/1.0";
    private static final String ACCEPT_HEADER = "application/vnd.github+json";
    private static final Set<String> ALLOWED_HOSTS = Set.of("api.github.com", "github.com", "codeload.github.com");

    private final ProjectAccessService projectAccessService;
    private final GitHubRepositoryCheckService gitHubRepositoryCheckService;
    private final SourceFileService sourceFileService;

    @Value("${github.import.connect-timeout-ms:5000}")
    private int connectTimeoutMs = 5000;

    @Value("${github.import.read-timeout-ms:30000}")
    private int readTimeoutMs = 30000;

    @Value("${source.upload.max-zip-bytes:73400320}")
    private long maxZipBytes = 70L * 1024L * 1024L;

    @Override
    public GitHubRepositoryImportResponse importRepository(UUID projectId) {
        SourceProject project = projectAccessService.requireCanUploadSource(projectId);
        if (project.getRepositoryUrl() == null || project.getRepositoryUrl().isBlank()) {
            throw new BadRequestException(
                    "This project does not have a linked GitHub repository URL. Set repositoryUrl first.");
        }

        GitHubRepositoryCheckResponse check = gitHubRepositoryCheckService.check(projectId);
        if (!check.isReachable()) {
            throw new BadRequestException("GitHub repository is not reachable: " + check.getMessage());
        }

        String branch = resolveBranch(project.getRepositoryBranch(), check);
        validateSafeBranch(branch);

        Path zipPath = null;
        try {
            zipPath = downloadRepositoryZipToTempFile(check.getOwner(), check.getRepositoryName(), branch, projectId);
            SourceFileUploadResponse upload = sourceFileService.importZip(
                    projectId,
                    Files.newInputStream(zipPath),
                    check.getRepositoryName() + "-" + branch.replace("/", "-") + ".zip",
                    true
            );

            return GitHubRepositoryImportResponse.builder()
                    .projectId(projectId)
                    .repositoryUrl(project.getRepositoryUrl())
                    .repositoryBranch(branch)
                    .provider("GITHUB")
                    .owner(check.getOwner())
                    .repositoryName(check.getRepositoryName())
                    .importStatus("SUCCESS")
                    .importedFileCount(upload.getSavedFiles())
                    .skippedFileCount(upload.getIgnoredFiles())
                    .totalFileCount(upload.getSavedFiles() + upload.getIgnoredFiles())
                    .message("Imported successfully from GitHub.")
                    .build();
        } catch (IOException e) {
            throw new BadRequestException("Failed to import GitHub repository ZIP: " + e.getMessage());
        } finally {
            if (zipPath != null) {
                try {
                    Files.deleteIfExists(zipPath);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private String resolveBranch(String storedBranch, GitHubRepositoryCheckResponse check) {
        if (storedBranch != null && !storedBranch.isBlank()) {
            return storedBranch.strip();
        }
        if (check.getDefaultBranch() != null && !check.getDefaultBranch().isBlank()) {
            return check.getDefaultBranch().strip();
        }
        if (check.getBranch() != null && !check.getBranch().isBlank()) {
            return check.getBranch().strip();
        }
        throw new BadRequestException("GitHub repository branch could not be resolved.");
    }

    Path downloadRepositoryZipToTempFile(String owner, String repositoryName, String branch, UUID projectId)
            throws IOException {
        validateRepositoryPart(owner, "owner");
        validateRepositoryPart(repositoryName, "repositoryName");
        validateSafeBranch(branch);

        URI initialUri = URI.create("https://api.github.com/repos/"
                + encodePathSegment(owner) + "/"
                + encodePathSegment(repositoryName) + "/zipball/"
                + encodePathSegment(branch));
        validateDownloadUri(initialUri);

        Path tempZip = Files.createTempFile("ai-toolcheck-github-" + projectId + "-", ".zip");
        try {
            HttpURLConnection first = openConnection(initialUri);
            int firstStatus = first.getResponseCode();
            if (isRedirect(firstStatus)) {
                String location = first.getHeaderField("Location");
                first.disconnect();
                URI redirectUri = validateRedirectLocation(initialUri, location);
                HttpURLConnection redirected = openConnection(redirectUri);
                try {
                    int redirectStatus = redirected.getResponseCode();
                    if (isRedirect(redirectStatus)) {
                        throw new BadRequestException("GitHub ZIP download redirected more than once.");
                    }
                    copySuccessfulResponse(redirected, redirectStatus, tempZip);
                } finally {
                    redirected.disconnect();
                }
            } else {
                try {
                    copySuccessfulResponse(first, firstStatus, tempZip);
                } finally {
                    first.disconnect();
                }
            }
            return tempZip;
        } catch (SocketTimeoutException e) {
            Files.deleteIfExists(tempZip);
            throw new BadRequestException("GitHub ZIP download timed out.");
        } catch (BadRequestException | IOException e) {
            Files.deleteIfExists(tempZip);
            throw e;
        }
    }

    URI validateRedirectLocation(URI sourceUri, String location) {
        if (location == null || location.isBlank()) {
            throw new BadRequestException("GitHub ZIP download returned an empty redirect location.");
        }
        URI redirectUri = sourceUri.resolve(location.strip());
        validateDownloadUri(redirectUri);
        return redirectUri;
    }

    private void copySuccessfulResponse(HttpURLConnection connection, int status, Path target) throws IOException {
        if (status == 404) {
            throw new BadRequestException("GitHub repository ZIP was not found for the selected branch.");
        }
        if (status == 403) {
            throw new BadRequestException("GitHub denied ZIP download; repository may be private or rate-limited.");
        }
        if (status != 200) {
            throw new BadRequestException("GitHub ZIP download failed with HTTP " + status + ".");
        }

        long copied = 0;
        byte[] buffer = new byte[8192];
        try (InputStream in = connection.getInputStream();
             OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                copied += read;
                if (copied > maxZipBytes) {
                    throw new BadRequestException("GitHub ZIP file is too large.");
                }
                out.write(buffer, 0, read);
            }
        }
    }

    private HttpURLConnection openConnection(URI uri) throws IOException {
        validateDownloadUri(uri);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestProperty("Accept", ACCEPT_HEADER);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setInstanceFollowRedirects(false);
        connection.setDoOutput(false);
        return connection;
    }

    private void validateDownloadUri(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new BadRequestException("GitHub ZIP download URL must use HTTPS.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new BadRequestException("GitHub ZIP download URL must not contain credentials.");
        }
        String host = uri.getHost();
        if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw new BadRequestException("GitHub ZIP download redirected to an unsupported host.");
        }
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private void validateRepositoryPart(String value, String fieldName) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,98}[A-Za-z0-9]|[A-Za-z0-9]")) {
            throw new BadRequestException("Invalid GitHub " + fieldName + ".");
        }
    }

    private void validateSafeBranch(String branch) {
        if (branch == null || branch.isBlank() || branch.length() > 120) {
            throw new BadRequestException("GitHub repository branch is invalid.");
        }
        if (!branch.equals(branch.strip())
                || branch.chars().anyMatch(ch -> ch <= 0x20 || ch == 0x7f)
                || branch.contains("\\")
                || branch.contains("?")
                || branch.contains("#")
                || branch.contains("://")
                || branch.equals("..")
                || branch.startsWith("../")
                || branch.endsWith("/..")
                || branch.contains("/../")) {
            throw new BadRequestException("GitHub repository branch contains unsafe characters.");
        }
    }

    private String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
