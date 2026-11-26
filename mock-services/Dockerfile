# One image for checkout-api, payment-gateway and payment-provider; APP_ROLE picks the role (CP-56).
# Build: docker build -t moriarty/mock-services:local mock-services

FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# Strip CRLF in case the wrapper was checked out on Windows.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
COPY src/ src/
RUN ./mvnw -B -DskipTests package

# Alpine JRE: busybox wget serves the compose healthcheck.
FROM eclipse-temurin:21-jre-alpine
ARG OTEL_JAVAAGENT_VERSION=2.20.1
WORKDIR /app
RUN addgroup -S app && adduser -S -G app app \
    && wget -q -O /app/opentelemetry-javaagent.jar \
       "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVAAGENT_VERSION}/opentelemetry-javaagent.jar" \
    && chmod 0644 /app/opentelemetry-javaagent.jar
COPY --from=build /build/target/mock-services.jar /app/app.jar

# Traces and logs go to the collector over OTLP; metrics are scraped from /actuator/prometheus,
# so the agent's own metric export is off to avoid duplicates (CP-59).
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar -XX:MaxRAMPercentage=75.0" \
    OTEL_TRACES_EXPORTER=otlp \
    OTEL_LOGS_EXPORTER=otlp \
    OTEL_METRICS_EXPORTER=none

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
