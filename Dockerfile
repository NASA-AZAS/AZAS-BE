FROM eclipse-temurin:21-jdk-noble@sha256:06c1f157dacf6b68198bd9a4186dd4c34ef24e65d7bbbe2a0cf8e62571ad38c9 AS builder

WORKDIR /workspace
COPY gradlew build.gradle settings.gradle gradle.properties gradle.lockfile ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew && ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre-noble@sha256:0d57a8e90c2bb844c5a8e1b2b71e11eff06dad7edcfd803cd21ed4ae203b2515 AS runtime

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 appuser \
    && useradd --uid 10001 --gid 10001 --create-home appuser

ENV AZAS_ENVIRONMENT=production \
    JAVA_TOOL_OPTIONS="-Xms32m -Xmx128m -XX:+UseSerialGC -XX:ReservedCodeCacheSize=32m -Xss512k -XX:+ExitOnOutOfMemoryError"

WORKDIR /app
COPY --from=builder --chown=10001:10001 /workspace/build/libs/azas-api.jar /app/azas-api.jar
USER 10001:10001

EXPOSE 8000
HEALTHCHECK --interval=5s --timeout=3s --start-period=60s --retries=12 \
    CMD ["curl", "--fail", "--silent", "--show-error", "--max-time", "2", "http://127.0.0.1:8000/health"]
ENTRYPOINT ["java", "-jar", "/app/azas-api.jar"]
