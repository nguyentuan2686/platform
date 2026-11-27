# Build context: repo root.  docker build -f services/__SERVICE__/Dockerfile -t __SERVICE__:local .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Dependencies first, as a layer of their own: it changes only with a pom or the Maven config, so the gha layer cache
# (cache-to mode=max) keeps the downloads between runs (a --mount=type=cache does not persist on hosted runners).
# Every reactor module pom is needed: Maven reads the whole reactor even with -pl. Sources are absent here, so the
# empty modules package without repackaging.
COPY .mvn/ .mvn/
COPY pom.xml ./
# new-service: java-poms (scripts/new-service.sh adds a COPY line per reactor module above this line)
RUN mvn -q -B -pl services/__SERVICE__ -am package -DskipTests -Dspring-boot.repackage.skip=true \
 && rm -rf libs/java/platform-starter/target services/*/target
COPY . .
RUN mvn -q -B -pl services/__SERVICE__ -am package -DskipTests \
 && cp services/__SERVICE__/target/__SERVICE__-*.jar /tmp/app.jar \
 && java -Djarmode=tools -jar /tmp/app.jar extract --layers --destination /tmp/extracted

FROM eclipse-temurin:21-jre AS agent
ARG OTEL_AGENT_VERSION=2.34.0
ARG OTEL_AGENT_SHA256=da44154cea128257779c8a782b9651fd7c0c6aa177cd3810f9a867ac8509555d
ADD --chmod=644 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
RUN echo "${OTEL_AGENT_SHA256}  /otel/opentelemetry-javaagent.jar" | sha256sum -c -

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=agent /otel/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
COPY --from=build /tmp/extracted/dependencies/ ./
COPY --from=build /tmp/extracted/spring-boot-loader/ ./
COPY --from=build /tmp/extracted/snapshot-dependencies/ ./
COPY --from=build /tmp/extracted/application/ ./
ENV PORT=__PORT__ \
    JAVA_TOOL_OPTIONS="-javaagent:/otel/opentelemetry-javaagent.jar -XX:MaxRAMPercentage=70" \
    OTEL_SERVICE_NAME=__SERVICE__ \
    OTEL_LOGS_EXPORTER=none \
    OTEL_METRICS_EXPORTER=otlp \
    OTEL_TRACES_EXPORTER=otlp
USER 10001
EXPOSE __PORT__
# No HEALTHCHECK: orchestrator probes /health/live and /health/ready.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
