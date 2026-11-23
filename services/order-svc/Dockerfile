# Build context: repo root.  docker build -f services/order-svc/Dockerfile -t order-svc:local .
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl services/order-svc -am package -DskipTests \
 && cp services/order-svc/target/order-svc-*.jar /tmp/app.jar \
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
ENV PORT=8080 \
    JAVA_TOOL_OPTIONS="-javaagent:/otel/opentelemetry-javaagent.jar -XX:MaxRAMPercentage=70" \
    OTEL_SERVICE_NAME=order-svc \
    OTEL_LOGS_EXPORTER=none \
    OTEL_METRICS_EXPORTER=otlp \
    OTEL_TRACES_EXPORTER=otlp
USER 10001
EXPOSE 8080
# No HEALTHCHECK: orchestrator probes /health/live and /health/ready.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
