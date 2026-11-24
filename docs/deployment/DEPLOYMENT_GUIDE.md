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

The backend never migrates or alters the schema. It connects as the restricted role `elecsarthi_app` (no DDL, no superuser), runs with `ddl-auto=validate`, and its jar contains no Flyway. The schema is applied by a **dedicated one-shot migration job** that runs *before* the backend starts, with privileged credentials the backend never receives. Never give `elecsarthi_app` ownership of the database or `GRANT ALL PRIVILEGES` on it: its table, function and sequence privileges come only from `V2` and `V6`.

### 3.1 Deployment chain (`docker-compose.yml`)

```text
postgres (postgres:18.3)  ->  migration  ->  runtime-role  ->  backend
```

Each step waits for the previous one with `condition: service_completed_successfully` (the first with `service_healthy`), so a failure anywhere stops the deployment before the backend starts:

| Step | Image | Credentials | What it does |
|---|---|---|---|
| `migration` | `flyway/flyway:12.11.0-alpine` | `POSTGRES_USER` / `POSTGRES_PASSWORD` (privileged bootstrap account) | `flyway migrate` over `src/main/resources/db/migration` (V1-V7). Each migration runs in its own transaction, so a failing one is rolled back and the job exits non-zero. Idempotent: an up-to-date database is a no-op. Configured through `FLYWAY_*` environment variables, so no password appears on a command line. |
| `runtime-role` | `postgres:18.3-bookworm` | same privileged account | `docker/db/set-runtime-password.sh` gives `elecsarthi_app` the password `DB_PASSWORD` and re-asserts `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS`. `V2` creates the role with a development password that is public in this repository, so the step **refuses** `elecsarthi_app_dev_pw` and any password shorter than 16 characters. |
| `backend` | built from `Dockerfile` | `DB_USER` (default `elecsarthi_app`) / `DB_PASSWORD` only | Starts only after both steps succeeded. |

Notes:
- PostgreSQL 18 images keep data in a versioned subdirectory; the volume is mounted at `/var/lib/postgresql` (mounting `/var/lib/postgresql/data` makes the container refuse to start).
- `MIGRATIONS_DIR` (optional) points the job at another directory of migration files; it exists for testing the failure path.
- Rotating `DB_PASSWORD` later: change it in `.env` and run `docker compose up -d` again; the `runtime-role` step applies it.

### 3.2 Without Docker (managed PostgreSQL)

1. Create an empty database owned by a privileged migration account (it must be able to `CREATE EXTENSION` for `btree_gist`, `citext`, `pg_trgm`, to create roles and to create `SECURITY DEFINER` functions; a superuser is simplest).
2. Run Flyway 12.11 (or the matching CLI/pipeline step) as that account: `flyway -url=jdbc:postgresql://HOST/elecsarthi_db -user=MIGRATION_USER -password=... -locations=filesystem:src/main/resources/db/migration migrate`.
3. Set the runtime password as the same account: `ALTER ROLE elecsarthi_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '<DB_PASSWORD>';`
4. Start the backend with `SPRING_DATASOURCE_USERNAME=elecsarthi_app`. Fail the deployment if step 2 or 3 fails.

### 3.3 Verifying a deployment

- `flyway info`: all of V1-V7 `Success`. The database has exactly **18 application tables** (plus `flyway_schema_history`; voter partitions are not counted).
- `tools/deploy/test_migration_job.ps1` runs the whole chain against a throwaway empty PostgreSQL 18.3 and checks the migration history, the table count, the runtime role's privileges and passwords, the failure path and the development-password refusal (`-WithBackend` also boots the backend image). It needs a running Docker daemon.
- Local development and the integration tests use a database migrated by `tools/db/run_tests.ps1`; the backend does not migrate it at startup.

### 3.4 Migrations (`src/main/resources/db/migration/`)

   - `V1__elecsarthi_schema.sql` (18 tables, triggers, partition functions)
   - `V2__runtime_role.sql` (Runtime role permissions for `elecsarthi_app`)
   - `V3__seed_election_type_field.sql` (271 official election field definitions)
   - `V4__canonical_customer_role.sql` (Canonical role alignment)
   - `V5__remove_legacy_customer_admin_role.sql` (Removal of legacy CUSTOMER_ADMIN role; SECURITY DEFINER partition management)
   - `V6__harden_security_definer_partition_functions.sql` (Input validation, table name escaping, search_path pinning)
   - `V7__audit_log_optimization_indexes.sql` (Partial indexes for registration JTI invalidation and global mobile toggle)

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
3. The included `docker/nginx/nginx.conf` and `docker-compose.yml` implement Option B with:
   - Port `80`: allows `/actuator/health` for load balancer probes and redirects all other traffic (`301 https://$host$request_uri`).
   - Port `443`: terminates `TLSv1.2` / `TLSv1.3` using `/etc/nginx/certs/fullchain.pem` and `/etc/nginx/certs/privkey.pem` (mounted read-only from `./docker/nginx/certs`).
   - Enforces `Strict-Transport-Security`, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy`, and `client_max_body_size 210M` with `proxy_request_buffering off` on `/api/v1/datasets/`.
4. Spring Boot automatically recognizes `https://` URLs for redirects, OpenAPI specs, and secure session cookies based on `X-Forwarded-Proto`.

> [!IMPORTANT]
> **TLS Activation Prerequisite:** The repository does **not** ship TLS certificates or private keys. TLS in the bundled `elecsarthi-nginx` container is **not operational** until valid PEM certificate and private key files are provisioned at `./docker/nginx/certs/fullchain.pem` and `./docker/nginx/certs/privkey.pem` (or external TLS termination such as AWS ALB / Cloudflare is configured in front of the service).

### Certificate Provisioning, Secret Handling & Renewal
1. **Environment Secrets:** Copy `.env.example` to `.env`, populate all required secrets (`POSTGRES_PASSWORD`, `DB_PASSWORD`, `ELECSARTHI_JWT_SECRET`, `ELECSARTHI_SECURITY_ACTIVATION_PEPPER`, `ELECSARTHI_SECURITY_ED25519_PRIVATE_KEY_HEX`, `ELECSARTHI_SECURITY_VOTER_MOBILE_AES_KEY_HEX`), and restrict permissions (`chmod 600 .env`). Never commit `.env` or certificate private keys.
2. **Certificate Mount Preparation:**
   ```bash
   mkdir -p ./docker/nginx/certs
   cp /etc/letsencrypt/live/api.elecsarthi.com/fullchain.pem ./docker/nginx/certs/fullchain.pem
   cp /etc/letsencrypt/live/api.elecsarthi.com/privkey.pem ./docker/nginx/certs/privkey.pem
   chmod 644 ./docker/nginx/certs/fullchain.pem
   chmod 600 ./docker/nginx/certs/privkey.pem
   ```
3. **Automated Certificate Renewal (Certbot + Zero-Downtime Reload):**
   - For Let's Encrypt DNS-01 (recommended when port 80 redirects all traffic except `/actuator/health`), or an external proxy/certbot deploy hook, copy renewed certificates to `./docker/nginx/certs/` and reload NGINX without dropping connections:
   ```bash
   certbot renew --quiet --deploy-hook "
     cp /etc/letsencrypt/live/api.elecsarthi.com/fullchain.pem /path/to/elecAppBackend/docker/nginx/certs/fullchain.pem && \
     cp /etc/letsencrypt/live/api.elecsarthi.com/privkey.pem /path/to/elecAppBackend/docker/nginx/certs/privkey.pem && \
     chmod 600 /path/to/elecAppBackend/docker/nginx/certs/privkey.pem && \
     docker compose -f /path/to/elecAppBackend/docker-compose.yml exec -T nginx nginx -s reload
   "
   ```
   - Alternatively, if terminating TLS externally (AWS ALB, Cloudflare, Envoy), route HTTPS traffic from the external terminator to the gateway/backend over a private VPC network and configure `X-Forwarded-Proto: https`.

---

## 6. Docker Deployment

### 4.1 Standalone Docker Run
Run this only against a database that was already migrated as described in section 3.
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
The provided `docker-compose.yml` deploys a secure stack. The one-shot `migration` and `runtime-role` steps (section 3.1) always run first and must succeed before the backend starts:
1. **NGINX:** Edge reverse proxy exposed on `80` (HTTP redirect) and `443` (TLS termination).
2. **Backend Application:** Internal Spring Boot container bound strictly to `127.0.0.1:8080` (not exposed to public network).
3. **PostgreSQL 18:** Database container bound strictly to `127.0.0.1:5432` on the host, communicating with backend over an isolated internal Docker bridge network (`elecsarthi-network`).

```bash
# Start backend, database, and NGINX reverse proxy
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
