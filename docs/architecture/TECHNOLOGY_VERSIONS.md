# ElecSarthi — Technology Versions

**Document Version:** 1.0.0  
**Last Verified:** 2026-11-24  
**Status:** Stable GA Production Baseline

---

## 1. Platform & Runtimes

| Component | Target Version | Installed / Verified | Notes |
|---|---|---|---|
| **Java** | 25 LTS | 25.0.1+8-LTS | Oracle OpenJDK 25 LTS |
| **Spring Boot** | 4.2.0 | 4.2.0 GA | Declared in `pom.xml` parent |
| **PostgreSQL** | 16+ / 18 | 18.3 x86_64 | Local host port 5498; 16+ compatibility preserved |
| **Apache Maven** | 3.9+ / 3.10 | 3.10.1 | Maven Wrapper (`mvnw`) |
| **Python** | 3.10+ | 3.14.5 | Used for seed and ERD scripts |

---

## 2. Backend Libraries & Dependencies

| Dependency | Group / Artifact | Version | Purpose |
|---|---|---|---|
| **Spring Boot Starter Actuator** | `org.springframework.boot:spring-boot-starter-actuator` | Managed (4.2.0) | Health, metrics, and readiness probes |
| **Spring Boot Starter Data JPA** | `org.springframework.boot:spring-boot-starter-data-jpa` | Managed (4.2.0) | Hibernate ORM, JPA repositories |
| **Spring Boot Starter Security** | `org.springframework.boot:spring-boot-starter-security` | Managed (4.2.0) | Authentication, session limits, role RBAC |
| **Spring Boot Starter Web** | `org.springframework.boot:spring-boot-starter-web` | Managed (4.2.0) | REST controllers, Jackson JSON, validation |
| **Spring Boot Starter Validation** | `org.springframework.boot:spring-boot-starter-validation` | Managed (4.2.0) | Jakarta Bean Validation 3.x |
| **Flyway Core** | `org.flywaydb:flyway-core` | Managed (4.2.0) | Database schema migrations |
| **Flyway PostgreSQL** | `org.flywaydb:flyway-database-postgresql` | Managed (4.2.0) | PostgreSQL dialect for Flyway |
| **PostgreSQL JDBC Driver** | `org.postgresql:postgresql` | Managed | Native JDBC driver with COPY API support |
| **Apache POI (OOXML)** | `org.apache.poi:poi-ooxml` | 5.3.0 | Streaming SAX reading of `.xlsx` datasets |
| **JJWT (API & Impl)** | `io.jsonwebtoken:jjwt-api`, `jjwt-impl`, `jjwt-jackson` | 0.12.6 | RFC 7519 JSON Web Token signing & validation |
| **Bouncy Castle** | `org.bouncycastle:bcprov-jdk18on` | 1.79 | Ed25519 signing and crypto support |
| **SpringDoc OpenAPI** | `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 2.8.3 | OpenAPI 3.0 specification & Swagger UI |
| **Lombok** | `org.projectlombok:lombok` | Managed | Boilerplate reduction (getters, builders) |
| **Testcontainers PostgreSQL** | `org.testcontainers:postgresql` | Managed | Disposable PostgreSQL container testing |
| **JUnit 5** | `org.junit.jupiter:junit-jupiter` | Managed | Unit and integration test framework |

---

## 3. PostgreSQL Extensions

| Extension | Required Version | Status | Purpose |
|---|---|---|---|
| `btree_gist` | 1.8+ | Available | GiST indexing for time-range exclusion constraints |
| `citext` | 1.8+ | Available | Case-insensitive text for emails and usernames |
| `pg_trgm` | 1.6+ | Available | Trigram fuzzy matching for voter name queries |
