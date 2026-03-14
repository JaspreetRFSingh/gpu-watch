# ─── Stage 1: Build ───────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Cache dependencies layer separately from source
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn clean package -DskipTests -q

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S gpuguard && adduser -S gpuguard -G gpuguard

# Copy fat jar from builder
COPY --from=builder /build/target/gpuguard-*.jar app.jar

# Use non-root user
USER gpuguard

EXPOSE 8080

# JVM tuning for container environments:
#   -XX:+UseContainerSupport        respect cgroup memory limits
#   -XX:MaxRAMPercentage=75.0       use 75% of container memory for heap
#   -XX:+ExitOnOutOfMemoryError     crash fast instead of limping
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]

HEALTHCHECK --interval=15s --timeout=5s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
