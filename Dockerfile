# syntax=docker/dockerfile:1

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 1001 ephemeral && mkdir -p /data/uploads && chown -R ephemeral /data
COPY --from=build /app/target/ephemeral.jar app.jar
USER ephemeral
EXPOSE 8080
ENV STORAGE_DIR=/data/uploads
VOLUME ["/data/uploads"]
# SerialGC + capped metaspace suit a small heap (10-user box); MaxRAMPercentage
# reads the compose mem_limit cgroup, so the heap tracks the container, not the host.
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=70", "-XX:MaxMetaspaceSize=128m", "-jar", "app.jar"]
