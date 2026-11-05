# syntax=docker/dockerfile:1

# ---- Build: compile and package with the Maven wrapper ---------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Wrapper and POM first: the dependency download below is cached until pom.xml changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp dependency:go-offline -q

COPY src src
# Tests need PostgreSQL and Redis, so they run in CI (and `mvn test`), not in the image build.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp package -DskipTests -q \
 && java -Djarmode=tools -jar target/url-shortener-*.jar extract --layers --launcher --destination /layers

# ---- Run: JRE only, non-root -------------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --no-create-home app

# One layer per rate of change: dependencies (rarely) ... application classes (every build), so a
# code change only re-ships the small last layer.
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./

USER app
EXPOSE 8080

# Size the heap from the container's memory limit rather than the host's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
