# AI ToolCheck — Production Operations Runbook

> This document covers the full production architecture, automated deploy flow, verification, rollback, log access, and incident procedures for the AI ToolCheck backend.
>
> **Security:** Do not add secret values to this file. Key names only.

---

## 1. Production Architecture

### Component Map

| Component | Location | Notes |
|---|---|---|
| DNS | DuckDNS `aitoolcheck-md.duckdns.org` | Points to EC2 Elastic IP `52.220.34.212` |
| Nginx | EC2 — port 80 and 443 | Terminates TLS, proxies to backend |
| TLS Certificate | Certbot (Let's Encrypt) on EC2 | Auto-renewal via cron/systemd |
| Spring Boot backend | Docker container on EC2 | Bound to `127.0.0.1:8080` only |
| RabbitMQ | Docker container on EC2 | Ports `127.0.0.1:5672` and `127.0.0.1:15672` — localhost only |
| MySQL database | AWS RDS | Not publicly accessible; EC2 connects via private endpoint |
| Neon pgvector | External managed Postgres | Used for vector search; credentials in env file |
| Docker images | GHCR (`ghcr.io/vominhduc1311/ai-toolcheck-backend`) | Built by GitHub Actions, pulled by EC2 |
| Secrets | `/etc/ai-toolcheck.env` on EC2 | Never in repo |

### Network Topology (ASCII)

```
Internet (HTTPS)
       |
       | port 443
       v
  [ Nginx on EC2 ]  <-- port 80: 301 redirect to HTTPS
       |
       | proxy_pass http://127.0.0.1:8080
       v
  [ Spring Boot container ]   (127.0.0.1:8080:8080 — not public)
       |              |
       v              v
  [ RabbitMQ ]   [ RDS MySQL ]   [ Neon pgvector ]
  (localhost)    (AWS private)   (external managed)
```

### Port Binding Summary

| Service | Host binding | Public? |
|---|---|---|
| Nginx HTTP | `0.0.0.0:80` | Yes — 301 redirect only |
| Nginx HTTPS | `0.0.0.0:443` | Yes — main entry point |
| Backend | `127.0.0.1:8080` | **No** — localhost only |
| RabbitMQ AMQP | `127.0.0.1:5672` | **No** |
| RabbitMQ Management | `127.0.0.1:15672` | **No** |
| RDS MySQL | Private endpoint | **No** |

---

## 2. Production URLs

| Purpose | URL |
|---|---|
| API base | `https://aitoolcheck-md.duckdns.org/api` |
| Health check | `GET https://aitoolcheck-md.duckdns.org/api/actuator/health` |
| Login | `POST https://aitoolcheck-md.duckdns.org/api/v1/auth/login` |
| Swagger UI | **Disabled in production** |
| OpenAPI JSON | **Disabled in production** |

> **Important:** Never use `http://52.220.34.212:8080` directly.
> The backend port is not publicly exposed. All traffic must go through Nginx on port 443.

---

## 3. Automated Deployment Flow

**Trigger:** Push to the `dev` branch, or manual `workflow_dispatch` in GitHub Actions.

**Concurrency:** Only one deploy runs at a time (`cancel-in-progress: false`). A queued deploy waits for the current one to finish.

### Job 1 — Build and Push (GitHub-hosted runner)

| Step | Action |
|---|---|
| 1 | Checkout source from `dev` branch |
| 2 | Set up Docker Buildx |
| 3 | Log in to GHCR using `GITHUB_TOKEN` |
| 4 | Build Docker image from `./Dockerfile` |
| 5 | Push two tags to GHCR: `:dev` (mutable) and `:<github.sha>` (immutable, SHA-pinned) |

GHA layer cache (`type=gha`) is used to speed up rebuilds.

### Job 2 — Deploy to EC2 (GitHub-hosted runner, SSH)

The runner SSHs into EC2 and executes a 10-step script:

| Step | Action |
|---|---|
| [1/10] | Navigate to `$EC2_APP_DIR` |
| [2/10] | `git fetch && git reset --hard origin/dev` — syncs `docker-compose.prod.yml` with latest commit |
| [3/10] | Verify `docker-compose.prod.yml` and `/etc/ai-toolcheck.env` exist |
| [4/10] | Verify Docker and Docker Compose are installed |
| [5/10] | Verify `RABBITMQ_USERNAME` and `RABBITMQ_PASSWORD` exist in env file |
| [6/10] | Log in to GHCR on EC2 using `GHCR_TOKEN` |
| [7/10] | Stop and disable legacy `systemd` service `ai-toolcheck` (prevents port 8080 conflict) |
| [8/10] | Remove stale non-compose-managed `ai-toolcheck-rabbitmq` container if found |
| [9/10] | `docker compose pull ai-toolcheck-backend` then `docker compose up -d --no-build` with the SHA-pinned image |
| [10/10] | Health check: polls `http://localhost:8080/api/actuator/health` every 5s, up to 30 attempts (150s total) |

**On health check failure:** The workflow prints the last 200 lines of backend logs and 80 lines of RabbitMQ logs, then exits with code 1.

> **Rule:** EC2 never builds Docker images. It only pulls pre-built images from GHCR.

---

## 4. Runtime Environment

The runtime env file lives on EC2 only:

```
/etc/ai-toolcheck.env
```

This file is **not committed to the repository** and must be protected (`chmod 600`).

The Docker Compose production file loads it via:

```yaml
env_file:
  - /etc/ai-toolcheck.env
```

### Required Environment Keys

The following keys must be present in `/etc/ai-toolcheck.env`. Values are never documented here.

| Key | Purpose |
|---|---|
| `MYSQL_URL` | JDBC connection URL for RDS MySQL |
| `MYSQL_USERNAME` | RDS MySQL username |
| `MYSQL_PASSWORD` | RDS MySQL password |
| `RABBITMQ_USERNAME` | RabbitMQ admin username |
| `RABBITMQ_PASSWORD` | RabbitMQ admin password |
| `APP_AUTH_JWT_SECRET` | JWT signing secret |
| `APP_AUTH_REFRESH_TOKEN_BYTES` | Byte length for refresh token generation |
| `AI_GEMINI_API_KEY` | Google Gemini API key |
| `VECTOR_DATASOURCE_URL` | Neon pgvector JDBC URL |
| `VECTOR_DATASOURCE_USERNAME` | Neon pgvector username |
| `VECTOR_DATASOURCE_PASSWORD` | Neon pgvector password |
| `SPRINGDOC_API_DOCS_ENABLED` | Must be `false` in production |
| `SPRINGDOC_SWAGGER_UI_ENABLED` | Must be `false` in production |

### Spring Boot Runtime Env (set in `docker-compose.prod.yml`)

| Variable | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `SERVER_PORT` | `8080` |
| `SPRING_RABBITMQ_HOST` | `ai-toolcheck-rabbitmq` (Docker network hostname) |
| `SPRING_RABBITMQ_PORT` | `5672` |

---

## 5. Post-Deploy Verification

### From your local machine (Windows PowerShell)

```powershell
# HTTPS health must return 200 + status UP
curl.exe -i "https://aitoolcheck-md.duckdns.org/api/actuator/health"

# HTTP must return 301 redirect to HTTPS
curl.exe -I "http://aitoolcheck-md.duckdns.org/api/actuator/health"

# Direct IP:8080 must fail or time out (backend is localhost-only)
curl.exe --max-time 10 -i "http://52.220.34.212:8080/api/actuator/health"
```

**Expected results:**

| Command | Expected |
|---|---|
| HTTPS health | `HTTP 200`, `{"status":"UP"}` |
| HTTP redirect | `HTTP 301`, `Location: https://...` |
| IP:8080 direct | `Connection refused` or timeout |

### From EC2 (SSH)

```bash
cd /home/ec2-user/AI_ToolCheck

# Confirm backend port is localhost-only
sudo docker ps --format "table {{.Names}}\t{{.Ports}}"
# Expected: ai-toolcheck-backend ... 127.0.0.1:8080->8080/tcp

# Health from localhost (same path as GitHub Actions health check)
curl -fsS http://localhost:8080/api/actuator/health && echo

# Health via HTTPS (through Nginx)
curl -fsS https://aitoolcheck-md.duckdns.org/api/actuator/health && echo

# HTTP to HTTPS redirect
curl -I http://aitoolcheck-md.duckdns.org/api/actuator/health
```

---

## 6. Logs and Debugging

All commands run from EC2 (SSH).

### Docker Compose stack status

```bash
cd /home/ec2-user/AI_ToolCheck

sudo docker compose \
  --env-file /etc/ai-toolcheck.env \
  -f docker-compose.prod.yml ps
```

### Backend logs

```bash
# Last 200 lines (static)
sudo docker compose \
  --env-file /etc/ai-toolcheck.env \
  -f docker-compose.prod.yml \
  logs --tail=200 ai-toolcheck-backend

# Live tail
sudo docker logs -f ai-toolcheck-backend --tail=100
```

### RabbitMQ logs

```bash
# Last 100 lines (static)
sudo docker compose \
  --env-file /etc/ai-toolcheck.env \
  -f docker-compose.prod.yml \
  logs --tail=100 ai-toolcheck-rabbitmq

# Live tail
sudo docker logs -f ai-toolcheck-rabbitmq --tail=50
```

### Nginx logs

```bash
sudo systemctl status nginx --no-pager
sudo nginx -t
sudo tail -n 100 /var/log/nginx/error.log
sudo tail -n 100 /var/log/nginx/access.log
```

---

## 7. Restart Procedures

All commands run from EC2 (SSH).

### Restart backend container only

Use when a new image has been pulled or backend needs a fresh start without touching RabbitMQ.

```bash
cd /home/ec2-user/AI_ToolCheck

sudo docker compose \
  --env-file /etc/ai-toolcheck.env \
  -f docker-compose.prod.yml \
  up -d --no-build ai-toolcheck-backend
```

### Restart RabbitMQ only

Use when RabbitMQ is unhealthy but the backend is not the issue. RabbitMQ data is persisted in a named Docker volume (`ai-toolcheck-rabbitmq-data`).

```bash
cd /home/ec2-user/AI_ToolCheck

sudo docker compose \
  --env-file /etc/ai-toolcheck.env \
  -f docker-compose.prod.yml \
  restart ai-toolcheck-rabbitmq
```

After restarting RabbitMQ, restart the backend as well so it can reconnect cleanly.

### Nginx: reload vs restart

**Reload** — preferred for config changes. Zero downtime; applies new config without dropping existing connections.

```bash
sudo nginx -t          # always validate config first
sudo systemctl reload nginx
```

**Restart** — use only if reload fails or Nginx is unresponsive. Drops all active connections briefly.

```bash
sudo nginx -t
sudo systemctl restart nginx
```

---

## 8. Rollback Procedure

Every deploy pushes an **immutable SHA-pinned image** to GHCR:

```
ghcr.io/vominhduc1311/ai-toolcheck-backend:<github.sha>
```

A rollback does not require re-building. It pulls the previous image and re-runs Docker Compose.

### Step 1 — Identify the last known-good SHA

From GitHub Actions: open the previous successful workflow run and copy the commit SHA from the deploy log (`IMAGE_REF=...`).

Or from your local machine:

```bash
git log --oneline origin/dev
```

### Step 2 — SSH into EC2 and roll back

```bash
cd /home/ec2-user/AI_ToolCheck

# Pull the known-good image
sudo env BACKEND_IMAGE="ghcr.io/vominhduc1311/ai-toolcheck-backend:<previous-sha>" \
  docker compose \
  --env-file /etc/ai-toolcheck.env \
  -f docker-compose.prod.yml \
  pull ai-toolcheck-backend

# Start with the known-good image
sudo env BACKEND_IMAGE="ghcr.io/vominhduc1311/ai-toolcheck-backend:<previous-sha>" \
  docker compose \
  --env-file /etc/ai-toolcheck.env \
  -f docker-compose.prod.yml \
  up -d --no-build
```

Replace `<previous-sha>` with the actual full or short Git SHA of the last known-good commit.

### Step 3 — Verify

```bash
curl -fsS http://localhost:8080/api/actuator/health && echo
curl -fsS https://aitoolcheck-md.duckdns.org/api/actuator/health && echo
```

### Important Limitations

> **Image rollback only changes the running container.** It does not revert `docker-compose.prod.yml`, application config, or database schema.
>
> If the bad commit also modified `docker-compose.prod.yml` or config files, create a **`git revert` PR into `dev`** and merge it. The next GitHub Actions deploy will apply the corrected compose file and image together.

---

## 9. Backup and Restore

### RDS MySQL Backup

- RDS automated backups must **not be disabled**.
- Recommended retention: **at least 7 days**.
- Take a manual snapshot before any risky database migration or schema change.

**AWS Console paths:**

```
RDS → Databases → [your db instance] → Configuration → Backup retention period
RDS → Databases → [your db instance] → Actions → Take snapshot
```

### Before Any Schema Change

1. Take a manual RDS snapshot.
2. Deploy the change.
3. Verify health and login endpoint.
4. Keep the snapshot for at least 24 hours after confirming stability.

### Restore Procedure

1. Restore the RDS snapshot to a new instance (do not overwrite the live instance until verified).
2. Point `MYSQL_URL` in `/etc/ai-toolcheck.env` to the restored instance endpoint.
3. Restart the backend container.
4. Verify health and login.

> **Never store database dumps or credentials in the repository.**

---

## 10. Certbot and HTTPS Operations

### Check certificate status

```bash
sudo certbot certificates
```

### Dry-run renewal test

```bash
sudo certbot renew --dry-run
```

Expected: `Congratulations, all simulated renewals succeeded.`

### After config changes or renewal

```bash
sudo nginx -t
sudo systemctl reload nginx
```

### Verify HTTPS is working end-to-end

```bash
# HTTP redirects to HTTPS
curl -I http://aitoolcheck-md.duckdns.org/api/actuator/health
# Expected: 301 Location: https://...

# HTTPS health check
curl -i https://aitoolcheck-md.duckdns.org/api/actuator/health
# Expected: 200 OK, {"status":"UP"}
```

### Certificate auto-renewal

Certbot installs a cron job or systemd timer for automatic renewal. Verify it is active:

```bash
sudo systemctl status certbot.timer --no-pager
# or
sudo crontab -l | grep certbot
```

---

## 11. Security Notes

| Rule | Detail |
|---|---|
| Backend port not public | `127.0.0.1:8080` only — never `0.0.0.0:8080` |
| RDS port not public | RDS security group must not allow `0.0.0.0/0` on port 3306 |
| RabbitMQ ports not public | `127.0.0.1:5672` and `127.0.0.1:15672` only |
| Swagger disabled in production | `SPRINGDOC_API_DOCS_ENABLED=false` and `SPRINGDOC_SWAGGER_UI_ENABLED=false` |
| Secrets never in repo | All secrets in `/etc/ai-toolcheck.env` on EC2 only |
| No secrets in GitHub issues or PRs | Rotate immediately if posted accidentally |
| No secrets in documentation | Key names only — never values |
| Admin password rotation | Rotate admin password if it is ever leaked or exposed |
| Screenshot hygiene | Do not share screenshots containing tokens, passwords, or Bearer headers |

---

## 12. Incident Checklists

### Health endpoint returning non-200 or DOWN

```bash
# On EC2:
sudo docker ps
sudo docker compose --env-file /etc/ai-toolcheck.env -f docker-compose.prod.yml ps
sudo docker logs --tail=200 ai-toolcheck-backend
curl -fsS http://localhost:8080/api/actuator/health
```

Check: container running? RabbitMQ healthy? DB reachable? Env keys present in `/etc/ai-toolcheck.env`?

---

### GitHub Actions deploy failed

1. Open the failed workflow run in GitHub Actions.
2. Expand step `[9/10]` — did the image pull succeed?
3. Expand step `[10/10]` — what did the health check log?
4. Check the backend log dump printed at the end of the failed run.
5. If the image pull failed: verify GHCR credentials (`GHCR_TOKEN` / `GHCR_USERNAME` secrets).
6. If health check timed out: SSH into EC2 and check `docker logs ai-toolcheck-backend`.

---

### Backend container is down

```bash
# On EC2:
sudo docker ps -a | grep ai-toolcheck-backend
sudo docker logs --tail=200 ai-toolcheck-backend

# Restart it
cd /home/ec2-user/AI_ToolCheck
sudo docker compose \
  --env-file /etc/ai-toolcheck.env \
  -f docker-compose.prod.yml \
  up -d --no-build ai-toolcheck-backend
```

---

### RabbitMQ container is unhealthy

```bash
sudo docker ps | grep rabbitmq
sudo docker logs --tail=100 ai-toolcheck-rabbitmq

# Restart RabbitMQ
cd /home/ec2-user/AI_ToolCheck
sudo docker compose \
  --env-file /etc/ai-toolcheck.env \
  -f docker-compose.prod.yml \
  restart ai-toolcheck-rabbitmq

# Then restart backend so it reconnects
sudo docker compose \
  --env-file /etc/ai-toolcheck.env \
  -f docker-compose.prod.yml \
  up -d --no-build ai-toolcheck-backend
```

---

### Nginx is down or returning errors

```bash
sudo systemctl status nginx --no-pager
sudo nginx -t
sudo tail -n 100 /var/log/nginx/error.log

# Reload (preferred)
sudo systemctl reload nginx

# Restart (if reload fails)
sudo systemctl restart nginx

# Verify HTTPS still works
curl -i https://aitoolcheck-md.duckdns.org/api/actuator/health
```

---

### HTTPS / certificate issue

```bash
sudo certbot certificates
sudo certbot renew --dry-run
sudo nginx -t
sudo systemctl reload nginx
curl -i https://aitoolcheck-md.duckdns.org/api/actuator/health
```

If the certificate is expired: run `sudo certbot renew` (not dry-run) and reload Nginx.

---

### Database connection issue

```bash
# Check backend logs for DB connection errors
sudo docker logs --tail=200 ai-toolcheck-backend | grep -i "connection\|datasource\|mysql\|hikari"

# Verify env keys are present (do not print values)
sudo grep -c '^MYSQL_URL=' /etc/ai-toolcheck.env
sudo grep -c '^MYSQL_USERNAME=' /etc/ai-toolcheck.env
sudo grep -c '^MYSQL_PASSWORD=' /etc/ai-toolcheck.env
```

Check: RDS instance is running in AWS Console. Verify EC2 security group is allowed as source in the RDS security group on port 3306.

---

### Disk nearly full on EC2

```bash
df -h
docker system df

# Remove unused images (safe — running containers are not affected)
sudo docker image prune -f

# Remove unused volumes (caution — verify before running)
sudo docker volume ls
```

> **Do not prune `ai-toolcheck-rabbitmq-data`** — it contains RabbitMQ queue state.

---

### Rollback needed

See [Section 8 — Rollback Procedure](#8-rollback-procedure).

Quick reference:

```bash
cd /home/ec2-user/AI_ToolCheck

sudo env BACKEND_IMAGE="ghcr.io/vominhduc1311/ai-toolcheck-backend:<previous-sha>" \
  docker compose --env-file /etc/ai-toolcheck.env -f docker-compose.prod.yml \
  pull ai-toolcheck-backend

sudo env BACKEND_IMAGE="ghcr.io/vominhduc1311/ai-toolcheck-backend:<previous-sha>" \
  docker compose --env-file /etc/ai-toolcheck.env -f docker-compose.prod.yml \
  up -d --no-build

curl -fsS http://localhost:8080/api/actuator/health && echo
```
