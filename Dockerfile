# syntax=docker/dockerfile:1

# ---- build: the whole Maven build runs in the image, so a server needs nothing but Docker ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
# dependencies first: this layer is rebuilt only when the pom changes
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src src
# the tests run in CI (they need Docker for Testcontainers), not while building the image
RUN ./mvnw -q -B package -DskipTests && mv target/schedule-*.jar app.jar

# ---- run: a JRE only, as an unprivileged user ----
FROM eclipse-temurin:25-jre
# curl is only for the container health check
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER app
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
