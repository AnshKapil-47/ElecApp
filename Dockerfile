# =============================================================================
# ElecSarthi Backend - Multi-stage Production Dockerfile
# Base Stack: Java 25 (Eclipse Temurin) + Spring Boot 4.2.0
# =============================================================================

# Stage 1: Build
FROM eclipse-temurin:25-jdk-noble AS builder
WORKDIR /build

# Copy build descriptor and wrapper
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# Resolve dependencies
RUN ./mvnw dependency:go-offline -B || true

# Copy source and compile package
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# Stage 2: Minimal Production JRE Runtime
FROM eclipse-temurin:25-jre-noble AS runner
WORKDIR /app

# Install curl for reliable health checking
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Security: Create unprivileged application user
RUN addgroup --system --gid 10001 elecsarthi && \
    adduser --system --uid 10001 --ingroup elecsarthi elecsarthi

# Copy artifact from builder
COPY --from=builder /build/target/elecAppBackend-*.jar /app/elecAppBackend.jar
RUN chown -R elecsarthi:elecsarthi /app

USER elecsarthi:elecsarthi

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/elecAppBackend.jar"]
