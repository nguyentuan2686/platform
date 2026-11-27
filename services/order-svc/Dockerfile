# Build context: repo root.  docker build -f services/order-svc/Dockerfile -t order-svc:local .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Dependencies first, as a layer of their own: it changes only with a pom or the Maven config, so the gha layer cache
# (cache-to mode=max) keeps the downloads between runs (a --mount=type=cache does not persist on hosted runners).
# Every reactor module pom is needed: Maven reads the whole reactor even with -pl. Sources are absent here, so the
# empty modules package without repackaging.
COPY .mvn/ .mvn/
COPY pom.xml ./
COPY libs/java/platform-starter/pom.xml libs/java/platform-starter/
COPY services/order-svc/pom.xml services/order-svc/
COPY services/reading-tuvi/pom.xml services/reading-tuvi/
COPY services/identity-svc/pom.xml services/identity-svc/
COPY services/payment-svc/pom.xml services/payment-svc/
# new-service: java-poms (scripts/new-service.sh adds a COPY line per reactor module above this line)
RUN mvn -q -B -pl services/order-svc -am package -DskipTests -Dspring-boot.repackage.skip=true \
 && rm -rf libs/java/platform-starter/target services/*/target
COPY . .
RUN mvn -q -B -pl services/order-svc -am package -DskipTests \
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
