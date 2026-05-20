# AI ToolCheck Backend

A Spring Boot Java 21 backend platform for automated API analysis, OpenAPI generation, AI-assisted enrichment, and test case management.

---

## Purpose

AI ToolCheck accepts source code ZIP uploads of Spring Boot / legacy Java projects and:

- Analyzes source structure and detects coding style (Modern vs Legacy)
- Parses API metadata (endpoints, parameters, schemas)
- Generates OpenAPI documentation
- Enriches API endpoints with AI-generated summaries via Gemini / Ollama (async via RabbitMQ)
- Generates and executes automated test cases against live APIs
- Provides project-scoped access control with member roles (MAINTAINER, EDITOR, VIEWER)

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.0.5 |
| Security | Spring Security + JWT (jjwt 0.12.6) |
| Database | MySQL (primary, via RDS) |
| Vector DB | Neon pgvector (external managed Postgres) |
| Messaging | RabbitMQ (compose-managed) |
| AI | Gemini API + Ollama (via WebClient) |
| Container | Docker + Docker Compose |
| Registry | GitHub Container Registry (GHCR) |
| Reverse Proxy | Nginx + Certbot (HTTPS) |
| CI/CD | GitHub Actions |
| Runtime | AWS EC2 (Amazon Linux) |

---

## Production

| Item | Value |
|---|---|
| Domain | `https://aitoolcheck-md.duckdns.org` |
| Health endpoint | `GET https://aitoolcheck-md.duckdns.org/api/actuator/health` |
| Login endpoint | `POST https://aitoolcheck-md.duckdns.org/api/v1/auth/login` |
| Swagger / OpenAPI | **Disabled in production** |
| Direct IP:8080 | **Must not be used — backend is localhost-only** |

---

## Completed Production Phases

| Phase | Description |
|---|---|
| Phase 1 | Elastic IP — stable public IP assigned to EC2 |
| Phase 2 | Runtime / secret hardening — all secrets in `/etc/ai-toolcheck.env` on EC2, none in repo |
| Phase 3 | Actuator health endpoint enabled; Swagger/OpenAPI disabled in production profile |
| Phase 4 | DuckDNS + Nginx + HTTPS (Certbot) + backend bound to `127.0.0.1:8080` only |
| Phase 5 | AWS Security Group hardening — port 8080 not publicly exposed |

---

## Deployment

Deployment is fully automated via GitHub Actions.

**Trigger:** Push to the `dev` branch (or manual `workflow_dispatch`).

**Flow:**
1. GitHub Actions builds the Docker image from `./Dockerfile`.
2. Image is pushed to GHCR with two tags:
   - `:dev` (mutable, latest)
   - `:<github.sha>` (immutable, SHA-pinned for rollback)
3. GitHub Actions SSHs into EC2.
4. EC2 pulls the new image from GHCR.
5. `docker compose up -d --no-build` restarts the backend container.
6. Health check loop validates `http://localhost:8080/api/actuator/health`.

**No manual Docker builds on EC2.** The EC2 instance only pulls pre-built images.

See `.github/workflows/deploy-backend-ec2.yml` for the full workflow definition.

---

## Local Development

- Use the `dev` Spring profile (`SPRING_PROFILES_ACTIVE=dev`).
- Configure local secrets via environment variables (never commit secrets).
- Swagger UI is available locally at `http://localhost:8080/api/swagger-ui/index.html`.
- Swagger is **disabled** in the production profile.
- See `src/main/resources/application-dev.yaml` for dev-specific configuration.

---

## Production Operations

For full production operations documentation — architecture overview, deploy flow, rollback, log access, incident checklists — see:

**[docs/runbook.md](docs/runbook.md)**

---

## Security

- **No secrets are stored in this repository.**
- All runtime secrets are stored on EC2 at `/etc/ai-toolcheck.env`.
- This file is never committed to the repository.
- Do not paste secrets into GitHub issues, pull requests, or documentation.
- Rotate credentials immediately if a leak is suspected.
