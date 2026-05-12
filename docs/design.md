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
5. RabbitMQ consumer processes each job:
   PENDING → RUNNING → (Ollama qwen2.5-coder:7b / Gemini) → SUCCESS or FAILED
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

## 8. Page-to-Backend API Mapping

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
| Generate via AI (async) | POST | `/api/v1/test-cases/generate` |

### Test Runs
| Action | Method | Endpoint |
|--------|--------|----------|
| Create test run | POST | `/api/v1/test-runs` |
| Execute (init + prepare) | POST | `/api/v1/test-runs/execute` |
| List by project | GET | `/api/v1/test-runs/project/{id}` |
| Get detail | GET | `/api/v1/test-runs/{runId}` |
| Prepare requests | POST | `/api/v1/test-runs/{runId}/prepare` |
| Execute run | POST | `/api/v1/test-runs/{runId}/execute` |

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

## 10. Test Execution Result States

### Per TestRunItem
| Scenario | `resultStatus` | `actualStatus` | `errorMessage` |
|----------|---------------|---------------|----------------|
| Assertion passed | `PASS` | e.g. 200 | null |
| Assertion failed | `FAIL` | actual HTTP code | assertion detail |
| Network error / refused | `ERROR` | null | connection error text |
| Target returned 401 | `FAIL` | 401 | assertion mismatch |
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

## 17. API Gaps Discovered

The following frontend pages/features have **no corresponding backend API** at time of inspection:

| Feature | Gap Description |
|---------|----------------|
| **List AI jobs by project** | No `GET /api/v1/ai-job-logs/project/{id}` endpoint exists. Only `GET /api/v1/ai-job-logs/{jobId}` (single) and `/statistics` (admin). Frontend must track jobIds from trigger responses or this endpoint needs to be added. |
| **TestResult detail endpoint** | No `GET /api/v1/test-results/{id}` controller exists. TestResult data is only accessible via TestRunItem inside TestRun detail. |
| **TestRunItem list** | No standalone `GET /api/v1/test-run-items/run/{runId}` endpoint. Items embedded in `GET /api/v1/test-runs/{id}` response. |
| **Source Analysis history** | Only one analysis result per project (1:1). No history list endpoint. |
| **Legacy Inference Logs list** | `LegacyInferenceLog` entity exists in DB; no controller exposes it to the frontend. Audit trail not accessible via API. |
| **AI Skill list for UI** | `GET /api/v1/ai-skills` returns paginated list but requires admin role in practice; no public read for skill selection UI. |
| **TestRun report export** | No `GET /api/v1/test-runs/{id}/report` or PDF/CSV export endpoint. Report page must be client-side rendered from run detail data. |
| **Source file content streaming** | `GET /api/v1/source-files/{id}` returns metadata + content inline; no streaming or pagination for large files. |
| **Dashboard aggregate stats** | No `/api/v1/dashboard` or summary endpoint. Dashboard must assemble stats from multiple API calls (projects, test runs, job logs). |
| **User profile update** | No `PATCH /api/v1/users/me` or profile update endpoint. Only password change via `/auth/change-password`. |

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
