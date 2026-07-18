# syntax=docker/dockerfile:1

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 1001 ephemeral && mkdir -p /data/uploads && chown -R ephemeral /data
COPY --from=build /app/target/ephemeral.jar app.jar
USER ephemeral
EXPOSE 8080
ENV STORAGE_DIR=/data/uploads
VOLUME ["/data/uploads"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
