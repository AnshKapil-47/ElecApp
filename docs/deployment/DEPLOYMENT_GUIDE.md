# ElecSarthi Backend — Production Deployment Guide

This guide details the steps required to deploy, configure, and maintain the ElecSarthi SaaS backend in staging and production environments.

---

## 1. Prerequisites & System Requirements

- **Runtime:** Java 25 (Eclipse Temurin or OpenJDK 25 LTS).
- **Database:** PostgreSQL 16+ (PostgreSQL 18 recommended).
  - Required Extensions: `pg_trgm`, `btree_gist`, `citext`.
- **Memory:** Minimum 4GB RAM (8GB+ recommended for production voter parsing).
- **Storage:** NVMe SSD storage for partitioned voter tables and GiST indexes.
- **Docker:** Docker Engine 24.0+ and Docker Compose v2.20+.

---

## 2. Environment Variables

Configure the following environment variables for production deployments:

| Variable | Description | Example / Default |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `prod` |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC connection URL | `jdbc:postgresql://db.internal:5432/elecsarthi_db` |
| `SPRING_DATASOURCE_USERNAME` | Application database user | `elecsarthi_app` |
| `SPRING_DATASOURCE_PASSWORD` | Strong database password | `[REDACTED]` |
| `ELECSARTHI_JWT_SECRET` | 256-bit Hex secret for JWT signing | `[MUST BE OVERRIDDEN IN PROD]` |
| `ELECSARTHI_SECURITY_ACTIVATION_PEPPER` | HMAC pepper for activation codes | `[MUST BE OVERRIDDEN IN PROD]` |
| `ELECSARTHI_SECURITY_ED25519_PRIVATE_KEY_HEX` | 32-byte Ed25519 private key in Hex | `[GENERATE WITH SECURE RANDOM]` |
| `ELECSARTHI_SECURITY_VOTER_MOBILE_AES_KEY_HEX` | 256-bit AES key in Hex for mobile encryption | `[GENERATE WITH SECURE RANDOM]` |

> [!CAUTION]
> The backend features a production fail-fast validator (`SecurityConfig.validateProductionSecurity()`).
> If `SPRING_PROFILES_ACTIVE=prod` is set and default development secrets are detected, the application will terminate immediately with an `IllegalStateException`.

---

## 3. Database Preparation & Migration

1. Create application database and user:
   ```sql
   CREATE DATABASE elecsarthi_db;
   CREATE USER elecsarthi_app WITH PASSWORD 'your_strong_password';
   GRANT ALL PRIVILEGES ON DATABASE elecsarthi_db TO elecsarthi_app;
   ```
2. Enable required extensions (run as superuser):
   ```sql
   \c elecsarthi_db
   CREATE EXTENSION IF NOT EXISTS pg_trgm;
   CREATE EXTENSION IF NOT EXISTS btree_gist;
   CREATE EXTENSION IF NOT EXISTS citext;
   ```
3. Flyway migrations (`src/main/resources/db/migration/`) executed by migration user/pipeline:
   - `V1__elecsarthi_schema.sql` (18 tables, triggers, partition functions)
   - `V2__runtime_role.sql` (Runtime role permissions for `elecsarthi_app`)
   - `V3__seed_election_type_field.sql` (271 official election field definitions)
   - `V4__canonical_customer_role.sql` (Canonical role alignment)
   - `V5__remove_legacy_customer_admin_role.sql` (Removal of legacy CUSTOMER_ADMIN role; SECURITY DEFINER partition management)

---

## 4. Least-Privilege Database Runtime User

In production, the backend container runs under the unprivileged PostgreSQL user `elecsarthi_app`, NOT `postgres` or a superuser:
- `elecsarthi_app` has `SELECT`, `INSERT`, `UPDATE` grants on standard tables.
- Append-only audit log: `SELECT` and `INSERT` only (triggers reject `UPDATE` and `DELETE`).
- Immutable sales lines: `SELECT` and `INSERT` only (no `UPDATE`, no `DELETE`).
- Dataset partition creation and detachment: Delegated via `SECURITY DEFINER` stored functions (`create_dataset_partitions`, `create_dataset_indexes`, `detach_and_drop_dataset_partitions`), allowing `elecsarthi_app` to safely manage partition lifecycles without requiring PostgreSQL table ownership or superuser rights.

---

## 5. TLS Configuration: Option A vs Option B

### Option A: Embedded Server TLS
To terminate TLS directly in Spring Boot:
Set `server.ssl.enabled=true`, configure `server.ssl.key-store`, `server.ssl.key-store-password`, and `server.ssl.key-store-type=PKCS12`.

### Option B: Reverse Proxy TLS Termination (Recommended for Production)
In modern containerized deployments (AWS ALB, Cloudflare, Nginx, HAProxy), TLS is terminated at the edge/reverse proxy:
1. The backend application runs with HTTP on port 8080 (`server.ssl.enabled=false`).
2. Spring Boot enables native forwarded header processing:
   ```yaml
   server:
     port: 8080
     forward-headers-strategy: native
   ```
3. The reverse proxy terminates TLS and forwards the client scheme and IP:
   ```nginx
   server {
       listen 443 ssl http2;
       server_name api.elecsarthi.com;

       ssl_certificate /etc/letsencrypt/live/api.elecsarthi.com/fullchain.pem;
       ssl_certificate_key /etc/letsencrypt/live/api.elecsarthi.com/privkey.pem;

       location / {
           proxy_pass http://127.0.0.1:8080;
           proxy_set_header Host $host;
           proxy_set_header X-Real-IP $remote_addr;
           proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
           proxy_set_header X-Forwarded-Proto $scheme;
           proxy_set_header X-Forwarded-Port $server_port;
       }
   }
   ```
4. Spring Boot automatically recognizes `https://` URLs for redirects, OpenAPI specs, and secure session cookies based on `X-Forwarded-Proto`.

---

## 6. Docker Deployment

### 4.1 Standalone Docker Run
```bash
docker build -t elecsarthi-backend:latest .
docker run -d \
  --name elecsarthi-backend \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/elecsarthi_db \
  -e SPRING_DATASOURCE_USERNAME=elecsarthi_app \
  -e SPRING_DATASOURCE_PASSWORD=strongpassword \
  -e ELECSARTHI_JWT_SECRET=f3b2... \
  -e ELECSARTHI_SECURITY_ACTIVATION_PEPPER=pepper... \
  elecsarthi-backend:latest
```

### 4.2 Docker Compose Deployment
```bash
# Start backend and PostgreSQL 18 with persistent volume
docker compose up -d

# View live container logs
docker compose logs -f backend

# Verify health status
docker compose ps
```

---

## 5. Health Checks & Monitoring

- **Liveness & Readiness Endpoint:** `GET /actuator/health`
  - Returns `{"status":"UP"}` when database connectivity and application context are healthy.
- **OpenAPI / Swagger UI Documentation:**
  - JSON Spec: `http://localhost:8080/api-docs`
  - Interactive UI: `http://localhost:8080/swagger-ui.html`

---

## 6. Backup & Maintenance

- **Partition Maintenance:**
  - Automated function `create_dataset_partitions(p_dataset_id)` creates partitions `voter_d<id>` and `voter_turnout_d<id>`.
  - Automated function `create_dataset_indexes(p_dataset_id)` builds specialized trgm and composite B-tree indexes.
- **Audit Log Backup:**
  - Table `audit_log` is immutable and append-only (`trg_audit_log_immutable`). Nightly WAL archiving or logical replication to cold storage is recommended.
