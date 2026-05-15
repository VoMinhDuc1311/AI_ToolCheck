# AI ToolCheck — Frontend Design Specification
> Generated from backend source inspection. All API routes are real controller mappings.
> Base path prefix: `/api/v1`

---

## 1. Product Overview

AI ToolCheck is a backend intelligence platform that:
- Accepts source code ZIP uploads for Spring Boot / legacy Java projects
- Analyzes source structure, detects coding style (MODERN vs LEGACY)
- Parses API metadata (endpoints, parameters, schemas)
- Generates OpenAPI documentation
- Enriches API endpoints with AI-generated summaries via Ollama / Gemini (async via RabbitMQ)
- Generates and executes automated test cases against live APIs
- Provides project-scoped access control with member roles

---

## 1.1 Build and Runtime Stack

- **Java**: Version 21
- **Spring Boot**: Version 4.0.5
- **Database**: MySQL and PostgreSQL supported (via `mysql-connector-j` and `postgresql` drivers)
- **ORM**: Spring Data JPA / Hibernate
- **Security**: Spring Security with JWT (`jjwt` 0.12.6)
- **Messaging**: RabbitMQ (`spring-boot-starter-amqp`)
- **Realtime**: WebSocket (`spring-boot-starter-websocket` is included in `pom.xml`, ready for Step 10)
- **AI Integration**: Custom Ollama and Gemini API clients using Spring WebFlux/WebClient
- **Testing**: JUnit, Spring Boot Test, Jayway JsonPath (for assertions)

---

## 1.2 Backend Project Structure

The codebase is organized by technical layers:
- `config`: Spring Boot configurations (SecurityConfig, RabbitMQConfig, WebClientConfig, etc.)
- `controller`: REST APIs (`@RestController`), mapping HTTP requests to Service layer.
- `service`: Business logic interfaces.
- `service/impl`: Implementations of business logic.
- `service/access`: `ProjectAccessService` for project-level authorization rules.
- `service/ai`: Integration with Ollama and Gemini (`GeminiApiClientService`, `OllamaApiClientService`, `AiModelRouterService`).
- `service/analysis`: Source code parsing and classification logic using JavaParser.
- `service/rabbitmq`: RabbitMQ Producers and Consumers (`AiTaskProducer`, `AiTaskConsumer`, `AiTaskPersistenceService`).
- `service/runner`: HTTP Test Execution logic (`TestHttpExecutor`, `TestRequestBuilder`).
- `repository`: Spring Data JPA repositories (`@Repository`).
- `model`: JPA Entities defining database tables and relationships.
- `dto`: Request and Response objects, organized by feature domains.
- `enums`: Domain constants (ProjectStatus, RunStatus, ExecutionStatus, ResultStatus, etc.).
- `security`: JWT authentication filters, entry points, and user details services.
- `exception`: Global exception handlers and custom exception classes.

---

## 1.3 Security and Auth Design

- **Login Flow**: `POST /api/v1/auth/login` returns an `AuthTokenResponse` containing `accessToken` and `refreshToken`.
- **Refresh Token Flow**: `POST /api/v1/auth/refresh` accepts a refresh token and issues a new access token.
- **Protected APIs**: Handled by `SecurityConfig`. By default, `anyRequest().authenticated()` is applied. Public endpoints include `/v1/auth/login`, `/v1/auth/refresh`, and Swagger UI.
- **Project Permissions**: Fine-grained access control is managed by `ProjectAccessService` which validates `UserRole` and `ProjectMemberRole`.
- **Rule - No Fake SecurityContext**: Background workers (like RabbitMQ consumers) do not have a standard HTTP request context. We MUST NOT mock or fake the `SecurityContext`.
- **Rule - Do Not Disable Security**: The global security configuration must remain intact.
- **Background Worker Fix**: Background async processes (RabbitMQ) bypass public HTTP controllers and directly call internal `@Service` methods that do not enforce `ProjectAccessService` security checks, ensuring async safety.
- **Step 10 WebSocket Note**: When implementing WebSocket handshake, only permit `/ws/**` in `SecurityConfig`. Do not weaken the existing HTTP API security rules.

---

## 1.4 Domain Model (Entity Relationships)

Based on the actual JPA `model` package:
- `SourceProject` -> `SourceFile` (1:N)
- `SourceProject` -> `SourceAnalysisResult` (1:1)
- `SourceProject` -> `ApiDocument` (1:1)
- `ApiDocument` -> `ApiDocumentVersion` (1:N)
- `SourceProject` -> `ApiEndpoint` (1:N)
- `SourceProject` -> `ApiSchema` (1:N)
- `SourceProject` -> `TestCase` (1:N)
- `ApiEndpoint` -> `TestCase` (1:N)
- `ApiDocumentVersion` -> `TestCase` (1:N)
- `SourceProject` -> `TestRun` (1:N)
- `TestRun` -> `TestRunItem` (1:N)
- `TestCase` -> `TestCaseInput` (1:1)
- `TestCase` -> `TestCaseAssertion` (1:N)
- `TestCase` -> `TestRunItem` (1:N)
- `TestRunItem` -> `TestResult` (1:1)
- `SourceProject` -> `AiJobLog` (1:N)
- `SourceProject` -> `LegacyInferenceLog` (1:N)

---

## 2. Roles and Permissions

### System-level Roles (`UserRole`)
| Role | Access |
|------|--------|
| `ADMIN` | All operations: user management, publish/unpublish docs, view all projects, job statistics |
| `MEMBER` | Own projects + shared projects only; no user management |

### Project-level Member Roles (`ProjectMemberRole`)
| Role | Capabilities |
|------|-------------|
| `MAINTAINER` | All project ops including AI trigger, upload, generate docs, delete test cases |
| `EDITOR` | Manage project, create test cases and test runs |
| `VIEWER` | Read-only: view project, prepare test runs |

### Project Visibility (`ProjectVisibility`)
| Value | Meaning |
|-------|---------|
| `PRIVATE` | Owner + members only |
| `PUBLIC_READ` | Anyone logged-in can view (but not modify) |

---

## 3. Project Permission Flags

These flags are returned by the backend and must drive frontend UI decisions:

```
canViewProject
canViewSourceFileContent
canManageProject
canManageMembers
canUpdateVisibility
canUploadSource
canGenerateDocs
canTriggerAiJob
canCreateTestCase
canCreateTestRun
canPrepareTestRun
canDeleteTestCase
```

> Source: `ProjectAccessService.buildPermissions()` → `GET /api/v1/source-projects/{id}`

**Rule:** Never show action buttons (Upload, Trigger AI, Generate, Delete) unless the corresponding flag is `true`. Do not rely on frontend role guessing.

---

## 4. Project Lifecycle Statuses (`ProjectStatus`)

```
NEW → UPLOADED → ANALYZING → ANALYZED → DOCUMENT_GENERATED → TEST_CASE_GENERATED → TEST_RUNNING → COMPLETED
                                                                                                  → FAILED
```

Display status as a progress indicator on Project Detail page.

---

## 5. Modern E2E Flow

```
1. Create project         POST /api/v1/source-projects
2. Upload source ZIP      POST /api/v1/source-projects/{id}/upload-zip   (multipart/form-data)
3. Analyze source         POST /api/v1/source-projects/{id}/analyze-source
4. View analysis result   GET  /api/v1/source-analysis-results/project/{id}
   → sourceStyle=MODERN, parserRecommended=true
5. Parse API metadata     POST /api/v1/source-projects/{id}/parse-api-metadata
6. View endpoints         GET  /api/v1/api-endpoints/project/{id}
7. Preview OpenAPI        GET  /api/v1/source-projects/{id}/openapi-json
8. Generate OpenAPI doc   POST /api/v1/source-projects/{id}/generate-openapi
9. View document          GET  /api/v1/api-documents/project/{id}
10. View versions         GET  /api/v1/api-documents/{docId}/versions
11. Generate test cases   POST /api/v1/test-cases/generate   (async, returns jobId)
    OR create manually    POST /api/v1/test-cases
12. List test cases       GET  /api/v1/test-cases/project/{id}
13. Create test run       POST /api/v1/test-runs
14. Prepare test run      POST /api/v1/test-runs/{id}/prepare
15. Execute test run      POST /api/v1/test-runs/{id}/execute
16. View results          GET  /api/v1/test-runs/{id}
```

---

## 6. Legacy AI Enrichment Flow

```
1. Upload + analyze source
   → sourceStyle=LEGACY, parserRecommended=false, aiRecommended=true
2. Parse API metadata (legacy endpoints extracted)
   POST /api/v1/source-projects/{id}/parse-api-metadata
3. View endpoints (aiEnrichedFlag=false initially)
   GET  /api/v1/api-endpoints/project/{id}
4. Trigger AI enrichment for all endpoints
   POST /api/v1/ai-job-logs/trigger/project/{id}/enrich-endpoints
   → Creates N AiJobLog rows (ExecutionStatus=PENDING)
   → Pushes N messages to RabbitMQ
5. RabbitMQ consumer (`AiTaskConsumer`) processes each job:
   PENDING → RUNNING → AI Router (Ollama Tier 1 → Gemini Tier 2 fallback) → SUCCESS or FAILED
   *Note: Consumer calls internal async-safe services (`aiJobLogService`, `documentEnrichmentService`) bypassing `ProjectAccessService` since there is no SecurityContext.*
6. Poll job status
   GET  /api/v1/ai-job-logs/{jobId}
7. After SUCCESS: endpoint reflects enrichment
   GET  /api/v1/api-endpoints/{endpointId}
   → aiEnrichedFlag=true
   → aiSummary populated
   → aiDescription populated
   → aiEnrichedAt populated
   → lastAiJobLogId populated
```

**Bug Fixed:** 
- The RabbitMQ worker previously failed due to lack of `SecurityContext`. Public HTTP methods enforce `ProjectAccessService`. To fix this, the worker now uses internal async-safe service methods, maintaining security rules without mocking/faking the `SecurityContext` or disabling security.

### AI Job States (`ExecutionStatus`)
| State | Display | Color |
|-------|---------|-------|
| `PENDING` | Queued | Gray |
| `RUNNING` | Processing… | Blue/Animated |
| `SUCCESS` | Completed | Green |
| `FAILED` | Failed | Red |
| `CANCELLED` | Cancelled | Orange |

### AI Job Types (`JobType`)
| Value | Meaning |
|-------|---------|
| `LEGACY_INFERENCE` | Skill 0 — Gemini reads legacy code, extracts endpoints |
| `DOCUMENT_ENRICHMENT` | Skill 1 — Ollama/Gemini enriches API docs |
| `TEST_CASE_GENERATION` | AI generates test cases for an endpoint |
| `FAILURE_ANALYSIS` | AI analyzes failed test results |
| `RESPONSE_SUMMARY` | AI summarizes API responses |

---

## 7. Page List

| # | Page | Route (suggested) | Auth Required |
|---|------|------------------|---------------|
| 1 | Login | `/login` | No |
| 2 | Dashboard | `/dashboard` | Yes |
| 3 | Source Projects | `/projects` | Yes |
| 4 | Project Detail | `/projects/:id` | Yes |
| 5 | Upload Source ZIP | `/projects/:id/upload` | Yes + canUploadSource |
| 6 | Source Files | `/projects/:id/files` | Yes |
| 7 | Source File Detail | `/projects/:id/files/:fileId` | Yes + canViewSourceFileContent |
| 8 | Source Analysis | `/projects/:id/analysis` | Yes |
| 9 | Source Analysis Report | `/projects/:id/analysis/report` | Yes |
| 10 | API Metadata | `/projects/:id/endpoints` | Yes |
| 11 | API Endpoint Detail | `/projects/:id/endpoints/:endpointId` | Yes |
| 12 | AI Enrichment Progress | `/projects/:id/ai-enrichment` | Yes |
| 13 | API Documents | `/projects/:id/documents` | Yes |
| 14 | API Document Version Detail | `/projects/:id/documents/:versionId` | Yes |
| 15 | AI Test Generation | `/projects/:id/test-cases/generate` | Yes + canCreateTestCase |
| 16 | Test Cases List | `/projects/:id/test-cases` | Yes |
| 17 | Test Case Detail | `/projects/:id/test-cases/:caseId` | Yes |
| 18 | Create Test Run | `/projects/:id/test-runs/create` | Yes + canCreateTestRun |
| 19 | Test Runs List | `/projects/:id/test-runs` | Yes |
| 20 | Test Run Detail | `/projects/:id/test-runs/:runId` | Yes |
| 21 | Test Run Execution Dashboard | `/projects/:id/test-runs/:runId/execute` | Yes + backend execute permission by executionMode |
| 22 | Test Result Detail | `/projects/:id/test-runs/:runId/results/:resultId` | Yes |
| 23 | Final Test Run Report | `/projects/:id/test-runs/:runId/report` | Yes |
| 24 | AI Job Logs | `/projects/:id/ai-jobs` | Yes |
| 25 | Admin — Users | `/admin/users` | ADMIN only |
| 26 | Settings (Profile) | `/settings` | Yes |
| 27 | Access Denied | `/403` | No |
| 28 | Session Expired | `/session-expired` | No |
| 29 | Resource Not Found | `/404` | No |

---

## 8. Backend API Map

### Auth
| Action | Method | Endpoint |
|--------|--------|----------|
| Login | POST | `/api/v1/auth/login` |
| Refresh token | POST | `/api/v1/auth/refresh` |
| Logout | POST | `/api/v1/auth/logout` |
| Get current user | GET | `/api/v1/auth/me` |
| Change password | POST | `/api/v1/auth/change-password` |

### Source Projects
| Action | Method | Endpoint |
|--------|--------|----------|
| Create project | POST | `/api/v1/source-projects` |
| Get project detail | GET | `/api/v1/source-projects/{id}` |
| List all (ADMIN) | GET | `/api/v1/source-projects` |
| List mine | GET | `/api/v1/source-projects/my` |
| List public | GET | `/api/v1/source-projects/public` |
| Update project | PUT | `/api/v1/source-projects/{id}` |
| Update visibility | PATCH | `/api/v1/source-projects/{id}/visibility` |
| Delete project | DELETE | `/api/v1/source-projects/{id}` |

### Project Members
| Action | Method | Endpoint |
|--------|--------|----------|
| List members | GET | `/api/v1/source-projects/{id}/members` |
| Add member | POST | `/api/v1/source-projects/{id}/members` |
| Update member role | PATCH | `/api/v1/source-projects/{id}/members/{memberId}` |
| Remove member | DELETE | `/api/v1/source-projects/{id}/members/{memberId}` |

### Source Files
| Action | Method | Endpoint |
|--------|--------|----------|
| Upload ZIP | POST | `/api/v1/source-projects/{id}/upload-zip` (multipart) |
| List files by project | GET | `/api/v1/source-files/project/{id}` |
| Get file detail | GET | `/api/v1/source-files/{fileId}` |

### Source Analysis
| Action | Method | Endpoint |
|--------|--------|----------|
| Trigger analysis | POST | `/api/v1/source-projects/{id}/analyze-source` |
| Get analysis result | GET | `/api/v1/source-analysis-results/project/{id}` |

### API Metadata
| Action | Method | Endpoint |
|--------|--------|----------|
| Parse metadata | POST | `/api/v1/source-projects/{id}/parse-api-metadata` |
| List endpoints | GET | `/api/v1/api-endpoints/project/{id}` |
| Get endpoint detail | GET | `/api/v1/api-endpoints/{endpointId}` |

### OpenAPI / Documents
| Action | Method | Endpoint |
|--------|--------|----------|
| Preview OpenAPI JSON | GET | `/api/v1/source-projects/{id}/openapi-json` |
| Generate & persist OpenAPI | POST | `/api/v1/source-projects/{id}/generate-openapi` |
| Get document by project | GET | `/api/v1/api-documents/project/{id}` |
| Get document by id | GET | `/api/v1/api-documents/{docId}` |
| Update document | PATCH | `/api/v1/api-documents/{docId}` |
| List versions | GET | `/api/v1/api-documents/{docId}/versions` |
| Publish document | PATCH | `/api/v1/api-documents/{docId}/publish` |
| Unpublish document | PATCH | `/api/v1/api-documents/{docId}/unpublish` |
| Get version detail | GET | `/api/v1/api-document-versions/{versionId}` |
| Update version | PATCH | `/api/v1/api-document-versions/{versionId}` |

### AI Job Logs
| Action | Method | Endpoint |
|--------|--------|----------|
| Trigger single AI job | POST | `/api/v1/ai-job-logs/trigger` |
| Trigger enrichment for project | POST | `/api/v1/ai-job-logs/trigger/project/{id}/enrich-endpoints` |
| Create pending job | POST | `/api/v1/ai-job-logs` |
| Get job by id | GET | `/api/v1/ai-job-logs/{jobId}` |
| Get statistics (ADMIN) | GET | `/api/v1/ai-job-logs/statistics` |

### Test Cases
| Action | Method | Endpoint |
|--------|--------|----------|
| Create test case | POST | `/api/v1/test-cases` |
| List by project | GET | `/api/v1/test-cases/project/{id}` |
| Get detail | GET | `/api/v1/test-cases/{caseId}` |
| Update | PATCH | `/api/v1/test-cases/{caseId}` |
| Delete (soft) | DELETE | `/api/v1/test-cases/{caseId}` |
| Generate via AI (async) | POST | `/api/v1/test-cases/generate` (or `/generate-async`) |

### Test Runs
| Action | Method | Endpoint |
|--------|--------|----------|
| Create test run | POST | `/api/v1/test-runs` |
| Execute (init async) | POST | `/api/v1/test-runs/execute` |
| List by project | GET | `/api/v1/test-runs/project/{id}` |
| Get detail | GET | `/api/v1/test-runs/{runId}` |
| Prepare requests | POST | `/api/v1/test-runs/{runId}/prepare` |
| Execute run (sync mode) | POST | `/api/v1/test-runs/{runId}/execute` |

### Admin — Users
| Action | Method | Endpoint |
|--------|--------|----------|
| Create user | POST | `/api/v1/users` |
| List all users | GET | `/api/v1/users` |
| Get user by id | GET | `/api/v1/users/{id}` |
| Update role | PATCH | `/api/v1/users/{id}/role` |
| Update status | PATCH | `/api/v1/users/{id}/status` |
| Reset password | PATCH | `/api/v1/users/{id}/reset-password` |

### AI Skills
| Action | Method | Endpoint |
|--------|--------|----------|
| List all (paginated) | GET | `/api/v1/ai-skills?page=1&pageSize=10` |
| Get by id | GET | `/api/v1/ai-skills/{id}` |
| Resolve by code | GET | `/api/v1/ai-skills/code/{skillCode}` |
| Create | POST | `/api/v1/ai-skills` |
| Update | PUT | `/api/v1/ai-skills/{id}` |

---

## 9. Key Enums — Display Mapping

### `ProjectStatus`
| Value | Display Label |
|-------|--------------|
| `NEW` | New |
| `UPLOADED` | Source Uploaded |
| `ANALYZING` | Analyzing… |
| `ANALYZED` | Analysis Complete |
| `DOCUMENT_GENERATED` | Document Generated |
| `TEST_CASE_GENERATED` | Test Cases Ready |
| `TEST_RUNNING` | Running Tests |
| `COMPLETED` | Completed |
| `FAILED` | Failed |

### `SourceStyle`
| Value | Display |
|-------|---------|
| `MODERN` | Modern (Parser recommended) |
| `LEGACY` | Legacy (AI enrichment recommended) |

### `RunStatus`
| Value | Display | Color |
|-------|---------|-------|
| `PENDING` | Pending | Gray |
| `RUNNING` | Running | Blue |
| `COMPLETED` | Completed | Green |
| `FAILED` | Failed | Red |
| `CANCELLED` | Cancelled | Orange |

### `ResultStatus` (per TestRunItem)
| Value | Display | Color |
|-------|---------|-------|
| `PASS` | PASS ✓ | Green |
| `FAIL` | FAIL ✗ | Red |
| `ERROR` | ERROR ⚠ | Orange |
| `SKIPPED` | Skipped | Gray |

### `CaseType`
`POSITIVE`, `NEGATIVE`, `BOUNDARY`, `VALIDATION`, `AUTHORIZATION`, `AUTHENTICATION`, `PERFORMANCE`, `INTEGRATION`

### `AssertionType`
`STATUS_CODE`, `JSON_PATH`, `RESPONSE_TIME`, `RESPONSE_TIME_MS`, `HEADER`, `BODY_CONTAINS`, `BODY_NOT_NULL`

### `ExecutionMode` (TestRun)
| Value | Meaning |
|-------|---------|
| `READ_ONLY` | No write operations against target API |
| `SAFE_WRITE` | Write ops with cleanup |
| `FULL_WRITE` | Full write, no rollback |

### `EnvironmentType`
`LOCAL`, `DEV`, `UAT`, `STAGING`, `SANDBOX`, `PROD`

### `UserStatus`
`ACTIVE`, `DISABLED`, `LOCKED`

---

## 10. Test Runner Design & Execution States

### Test Runner Domain Concepts
- **`TestCase`**: Defines the request input (`TestCaseInput`) and expected results (`TestCaseAssertion`).
- **`TestRun`**: Groups an execution batch for multiple test cases. Has a `RunStatus` lifecycle (PENDING, RUNNING, COMPLETED, FAILED).
- **`TestRunItem`**: Represents a single case execution within a run. Has an `ExecutionStatus` lifecycle (PENDING, RUNNING, SUCCESS, FAILED).
- **`TestResult`**: Stores the actual outcome of a `TestRunItem` execution, including `actualStatus`, `actualResponseJson`, `responseTimeMs`, `resultStatus` (PASS, FAIL, ERROR), and `errorMessage`.

### Per TestRunItem Execution Result (`ResultStatus`)
| Scenario | `resultStatus` | `actualStatus` | `errorMessage` |
|----------|---------------|---------------|----------------|
| Assertion passed | `PASS` | e.g. 200 | null |
| Assertion failed | `FAIL` | actual HTTP code | assertion detail / mismatch |
| Network error / refused | `ERROR` | 0 | connection error text |
| Target returned 401 | `FAIL` | 401 | HTTP 401 from target |
| Blocked / precondition | `FAIL` or `SKIPPED` | null | `blockedReason` |

### Run-level Aggregation (compute client-side from items)
```
totalItems    = testRunItems.length
passCount     = items where resultStatus == PASS
failCount     = items where resultStatus == FAIL
errorCount    = items where resultStatus == ERROR
successRate   = passCount / totalItems * 100   (only after run COMPLETED)
```

> **Never show successRate, healthScore, or testCoverage before a test run has COMPLETED.**
> **Note on Realtime Counters**: Realtime counters in `TestRunRealtimeEvent` are backend progress snapshots at the time of publishing. The final report, `successRate`, and `healthScore` MUST be re-fetched from the `TestRun` detail endpoint and calculated from persisted `TestResult` records. WebSocket events are NOT the final source of truth for reports.

---

## 10.1. Test Execution Flow (Step 9 implementation)

**Endpoint:** `POST /api/v1/test-runs/{id}/execute`

1. **Controller**: `TestRunController.executeById(UUID)` receives the request.
2. **Service & Validation**: 
   - `TestRunServiceImpl.execute(UUID)` is called.
   - Validates TestRun existence. Guards against concurrent double-execution.
   - Checks project permission via `ProjectAccessService.requireCanExecuteTestRun(projectId, executionMode)`. (Requires MAINTAINER or EDITOR for READ_ONLY; MAINTAINER for SAFE_WRITE/FULL_WRITE).
3. **Load Items**: Loads deterministic list of `TestRunItem` for the run. Updates TestRun status to `RUNNING`.
4. **Execution Loop**: For each `TestRunItem`:
   - Set item status to `RUNNING`.
   - Extract `TestCase` and `TestCaseInput`. If missing input, marks as `FAILED` (with `ERROR` ResultStatus) and skips.
   - `TestRequestBuilder.build()` creates a `PreparedHttpRequestResponse`.
   - `TestHttpExecutor.execute()` performs the actual target HTTP request. Handles connection/timeout errors returning an `ExecutedHttpResponse` (prevents crashing).
   - Maps actual response to `HttpActualResponseDto` (contains `actualStatus`, `responseBody`, `responseTimeMs`).
   - `TestResultService.saveRawTestResult()` persists the raw outcome.
   - `RuleEngineService.evaluate()` evaluates expected assertions against actual result. Updates `TestResult`.
   - Final `ResultStatus` maps to `ExecutionStatus` (PASS -> SUCCESS, FAIL/ERROR/SKIPPED -> FAILED), saved to `TestRunItem`.
5. **Final Aggregation**: Sets final `TestRun` status to `FAILED` (if any item failed) or `COMPLETED`.

---

## 11. Loading / Empty / Error States

### Forbidden Rules
Never render: `undefined`, `null`, `NaN`, `[object Object]`

### Safe Fallback Values
| Field | Before data available | Display |
|-------|-----------------------|---------|
| Source Files count | Before upload | `0` |
| Total Endpoints | Before parse | `0` |
| API Documents | Before generation | `0` |
| Test Coverage | Before test run | `N/A` |
| Health Score | Before test run | `Pending` |
| Success Rate | Before test run | `N/A` |
| AI Summary | Before enrichment | `N/A` |
| AI Description | Before enrichment | `N/A` |
| aiEnrichedAt | Before enrichment | `Not enriched yet` |
| Analysis result | Before analysis | `No analysis yet` |
| Test cases | Empty project | `No test cases yet` |
| Test runs | Empty project | `No test runs yet` |

### Loading States
- Show skeleton loaders for list pages and detail sections
- Show spinner inside action buttons during mutation
- Disable action button while request in flight
- Never show stale data after a successful mutation without refresh

### Error States
| HTTP Code | Page Behavior |
|-----------|--------------|
| 401 | Redirect to `/session-expired` |
| 403 | Show `/403` Access Denied |
| 404 | Show `/404` Resource Not Found |
| 400 | Show inline validation message |
| 500 | Show toast "Server error, please retry" |

---

## 12. Data Accuracy Rules

### Before source upload
- Source Files = `0`
- Source Profile = empty
- API Endpoints = `0`
- API Documents = `0`
- Test Coverage = `N/A`
- Health Score = `Pending`

### Before metadata parse
- Total Endpoints = `0`
- All endpoint lists must be empty

### Before OpenAPI generation
- API Documents = `0`
- Document Status = `Not generated`

### Before test execution
- Test Coverage = `N/A`
- Health Score = `Pending`
- Success Rate = `N/A`

### Before AI enrichment
- `aiEnrichedFlag = false`
- AI Summary = `N/A`
- AI Description = `N/A`

### After AI enrichment
- `aiEnrichedFlag = true`
- AI Summary = rendered text
- AI Description = rendered text
- `aiEnrichedAt` = formatted timestamp
- `lastAiJobLogId` = link to job log

### Derived metrics computation rule
> Metrics like Success Rate, Health Score, and Coverage **must be computed from real TestResult records only**. Never hardcode or estimate. Never display them before `runStatus = COMPLETED`.

---

## 13. Data Source Rule

### StitchAI Prototype
- **May** use static mock data and demo fixtures for visual demonstration only
- Static constants allowed: labels, enum display names, route names, colors, empty-state messages
- Mock data must be clearly identified and isolated (e.g., `mockData/` directory)

### Production React Frontend
- **Must not** hardcode any business data: project names, endpoint paths, test results, AI summaries
- **Must** call real backend APIs for all:
  - Project / source / analysis data
  - API metadata and documents
  - Test cases, test runs, test results
  - AI job logs and enrichment status
  - User and member data
- Token management: store `accessToken` in memory; `refreshToken` in httpOnly cookie or secure storage
- Call `GET /api/v1/auth/me` on app load to bootstrap user context
- Re-fetch after every mutation that changes displayed state

---

## 14. Admin / Users / Settings Flow

### Admin Users Page (`/admin/users`)
- Only visible if `currentUser.role == ADMIN`
- APIs:
  - `GET /api/v1/users` — list all
  - `POST /api/v1/users` — create new account
  - `PATCH /api/v1/users/{id}/role` — promote/demote
  - `PATCH /api/v1/users/{id}/status` — activate/disable/lock
  - `PATCH /api/v1/users/{id}/reset-password` — admin reset

### Settings Page (`/settings`)
- Available to all authenticated users
- APIs:
  - `GET /api/v1/auth/me` — load profile
  - `POST /api/v1/auth/change-password` — change own password
  - `POST /api/v1/auth/logout` — revoke session

### AI Job Statistics (Admin Dashboard widget)
- `GET /api/v1/ai-job-logs/statistics` — ADMIN only
- Fields: totalJobs, totalSuccessfulJobs, totalFailedJobs, totalTokenInput, totalTokenOutput

---

## 15. AI Enrichment Progress Page Behavior

Page: `/projects/:id/ai-enrichment`

1. Load endpoint list: `GET /api/v1/api-endpoints/project/{id}`
2. For each endpoint without `aiEnrichedFlag=true`, show pending state
3. Trigger all: `POST /api/v1/ai-job-logs/trigger/project/{id}/enrich-endpoints`
4. Poll individual jobs: `GET /api/v1/ai-job-logs/{jobId}` every 3–5s
5. Stop polling when `executionStatus` is `SUCCESS` or `FAILED`
6. Re-fetch endpoint to display updated `aiSummary`, `aiDescription`, `aiEnrichedAt`

### Per-endpoint display states during enrichment
| aiEnrichedFlag | Last job status | Display |
|----------------|----------------|---------|
| `false` | none | `Not enriched` |
| `false` | `PENDING` | `Queued` |
| `false` | `RUNNING` | `Processing…` (animated) |
| `true` | `SUCCESS` | `Enriched ✓` + timestamp |
| `false` | `FAILED` | `Failed — [errorMessage]` |

---

## 16. Project Sharing Panel Behavior

Page: Project Detail → Members tab

- Load: `GET /api/v1/source-projects/{id}/members`
- Add by email: `POST /api/v1/source-projects/{id}/members` with `{ userEmail, role }`
- Update role: `PATCH /api/v1/source-projects/{id}/members/{memberId}`
- Remove: `DELETE /api/v1/source-projects/{id}/members/{memberId}`
- Only show panel if `canManageMembers = true`
- Add member mutation must use isolated loading state (not page-wide loader)

---

## 17. API Gaps / Open Questions

The following features have **no corresponding backend API** or are partially implemented:

| Feature | Status | Description |
|---------|--------|-------------|
| **List AI jobs by project** | Missing | No `GET /api/v1/ai-job-logs/project/{id}` endpoint exists. Only single lookup or admin stats. |
| **TestResult detail endpoint** | Partially Implemented | No standalone `/test-results/{id}` controller. Data is embedded in TestRun detail response. |
| **TestRunItem list** | Partially Implemented | Embedded in `GET /api/v1/test-runs/{id}` response. |
| **WebSocket Realtime Test Execution** | Proposed for Step 10 | See Section 22 for proposal. |
| **Dashboard aggregate stats** | Missing | Frontend must assemble stats from multiple calls. |
| **User profile update** | Missing | Only password change is supported via `/auth/change-password`. |

---

## 18. Security Rules for Frontend

1. **No token → redirect to `/login`** for all protected routes
2. **Role guard:** render admin routes only when `currentUser.role == ADMIN`
3. **Permission guard:** render action buttons only when corresponding project permission flag is `true`
4. **Token refresh:** on 401 response, attempt one silent refresh via `POST /api/v1/auth/refresh`, then retry original request. On refresh failure, redirect to `/session-expired`
5. **Never call** `enrichEndpointData` (HTTP-authenticated) from any background/worker context
6. **Never expose** raw JWT or refresh token in application state logs

---

## 19. Source Analysis Result Fields

From `SourceAnalysisResult` entity:

| Field | Type | Meaning |
|-------|------|---------|
| `sourceStyle` | `MODERN` / `LEGACY` | Determines recommended workflow |
| `parserRecommended` | Boolean | If true, use metadata parser flow |
| `aiRecommended` | Boolean | If true, use AI enrichment flow |
| `annotationScore` | Integer | Code annotation quality (0–100) |
| `structureScore` | Integer | Code structure quality (0–100) |
| `totalFiles` | Integer | All files in ZIP |
| `analyzableFiles` | Integer | Files the analyzer could process |
| `parsedSuccessFiles` | Integer | Successfully parsed |
| `parsedFailedFiles` | Integer | Failed to parse |
| `parseSuccessRate` | Double | `parsedSuccess / analyzable` |
| `summary` | String | Human-readable analysis summary |
| `currentFlag` | Boolean | Whether this is the active analysis |

---

## 20. API Endpoint Detail Fields

From `ApiEndpoint` entity — used on Endpoint Detail page:

| Field | Display Name | Notes |
|-------|-------------|-------|
| `httpMethod` | Method | GET, POST, PUT, PATCH, DELETE |
| `endpointPath` | Path | e.g. `/api/v1/users/{id}` |
| `controllerName` | Controller | Java class name |
| `methodName` | Method Name | Java method name |
| `operationId` | Operation ID | |
| `tagName` | Tag | Swagger tag |
| `authRequired` | Auth Required | Boolean badge |
| `deprecatedFlag` | Deprecated | Boolean badge |
| `activeFlag` | Active | Boolean |
| `staleFlag` | Stale | Boolean — show warning if true |
| `aiEnrichedFlag` | AI Enriched | Boolean badge |
| `aiSummary` | AI Summary | Text, `N/A` if null |
| `aiDescription` | AI Description | Text, `N/A` if null |
| `exampleRequestJson` | Example Request | JSON, show in code block |
| `exampleResponseJson` | Example Response | JSON, show in code block |
| `openapiFragmentJson` | OpenAPI Fragment | JSON |
| `aiEnrichedAt` | Enriched At | Formatted datetime |
| `lastAiJobLogId` | Last AI Job | Link to job log detail |

---

## 21. Backend Coding Conventions

- **DTOs**: Standard class-based DTOs are used (not Java 14 records), organized by domain.
- **Lombok**: Heavily used for Getters, Setters, NoArgsConstructor, AllArgsConstructor, and Builder pattern.
- **Identifiers**: `UUID` is used for all entity primary keys.
- **Timestamps**: Uses `java.time.LocalDateTime` for `createdAt`, `updatedAt`, `deletedAt`.
- **API Responses**: All controllers wrap successful responses in a generic `ApiResponse<T>` wrapper (contains `code`, `message`, `data`, `timestamp`).
- **Exceptions**: Custom runtime exceptions (e.g., `BadRequestException`, `ResourceNotFoundException`) mapped by global exception handler to standard error responses.
- **Logging**: SLF4J `@Slf4j` is used across services.

---

## 22. Step 10: WebSocket Realtime Test Execution (Design Proposal)

**Goal**: When a test execution is triggered (`POST /api/v1/test-runs/{id}/execute`), the backend will continue to execute and save `TestResult` to the database exactly like Step 9, while simultaneously publishing realtime STOMP events to notify subscribers of execution progress.

### WebSocket Configuration Proposal
- **Endpoint**: `/ws`
- **STOMP App Destination Prefix**: `/app`
- **STOMP Broker Prefix**: `/topic`
- **Topic Pattern**: `/topic/projects/{projectId}/test-runs/{testRunId}`

### Event Types
- `RUN_STARTED`: Emitted before the batch execution begins.
- `ITEM_STARTED`: Emitted before executing an individual `TestRunItem`.
- `ITEM_COMPLETED`: Emitted *only after* a `TestResult` is successfully persisted to the database.
  - *Publish Rule*: Must be emitted only after TestResult save/update succeeds.
  - If execution uses one large transaction, consider after-commit publishing if frontend stale-read issue appears.
  - Step 10 initial implementation may keep safe publish after save/update, but must not publish before DB persistence.
- `RUN_COMPLETED`: Emitted after the completed run state is persisted.
- `RUN_FAILED`: Emitted after a failed run state is persisted.

### DTO Proposal (`TestRunRealtimeEvent`)
```java
public class TestRunRealtimeEvent {
    private UUID projectId;
    private UUID testRunId;
    private UUID testRunItemId; // Null for RUN-level events
    private UUID testCaseId;
    private String eventType; // RUN_STARTED, ITEM_COMPLETED, etc.
    private String runStatus; // PENDING, RUNNING, COMPLETED, FAILED
    private String itemStatus; // PENDING, RUNNING, SUCCESS, FAILED
    private String resultStatus; // PASS, FAIL, ERROR
    private String caseCode;
    private String caseName;
    private Integer sortOrder;
    private Integer actualStatus;
    private Integer responseTimeMs;
    private String errorMessage;
    private String blockedReason;
    private String actualResponseJson; // Optional, might be truncated for WS payload limit
    private Integer totalItems;
    private Integer completedItems;
    private Integer successItems;
    private Integer failedItems;
    private Integer errorItems;
    private LocalDateTime occurredAt;
}
```

### Safety and Security Rules
- **Non-blocking Delivery**: WebSocket publish failures MUST ONLY log warnings and MUST NOT break or abort the actual HTTP test execution process.
- **Integrity**: Realtime updates MUST NOT change the core `PASS/FAIL/ERROR` logic, and MUST NOT interfere with standard database persistence.
- **Security**: 
  - Do NOT weaken existing HTTP API security rules.
  - Permit `/ws/**` in Spring Security config only if strictly required for the STOMP handshake during local development.
  - Future enhancement: Require JWT authentication during STOMP CONNECT.
