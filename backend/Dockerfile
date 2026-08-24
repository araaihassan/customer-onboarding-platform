# Build stage: Gradle wrapper needs a JDK, and the wheel wrapper jar/version
# are already pinned in gradle/wrapper -- this just needs a matching JDK major
# version (build.gradle.kts pins the toolchain to 21).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy just the wrapper and build files first so dependency resolution layers
# are cached across builds that only change application source.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# Normalizes line endings and the executable bit regardless of how the build
# context's checkout handled them (a Windows checkout with core.autocrlf can
# leave gradlew CRLF, which breaks its shebang under /bin/sh).
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN ./gradlew --version

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# Runtime stage: JRE only, no JDK/Gradle needed to run the built jar.
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
COPY --from=build /app/build/libs/*.jar app.jar
USER appuser

EXPOSE 8080
# Render sets PORT; local docker run without it falls back to 8080.
ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${PORT:-8080}"]
