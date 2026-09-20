# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- build stage
FROM maven:3.9.9-amazoncorretto-21 AS build
WORKDIR /workspace

# Dependencies resolve in their own layer so code changes do not re-download Maven Central.
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
# Tests run here, not as a separate workflow step: a failing test fails the
# image build, so nothing untested can reach ECR.
RUN mvn -B -ntp clean verify

# --------------------------------------------------------------- runtime stage
# Amazon Corretto: AWS's own OpenJDK build, supported on the platform this runs
# on. Corretto publishes no Alpine JRE, only a JDK, so this base is ~490MB
# against ~285MB for a Temurin JRE. The cost is image pull time on a cold
# Fargate task; jlink in the build stage would trim it back if that matters.
FROM amazoncorretto:21-alpine-jdk

# curl backs the container-level HEALTHCHECK and the ECS health check in taskdef.json.
RUN apk add --no-cache curl \
 && addgroup -S app \
 && adduser -S -G app -h /app app

WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/todo-app.jar /app/app.jar
USER app

EXPOSE 8080

# MaxRAMPercentage keeps the heap inside the Fargate task memory limit.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
