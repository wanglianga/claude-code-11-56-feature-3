# ---- build -----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Warm the dependency cache before copying sources so code edits rebuild fast.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- runtime ---------------------------------------------------------
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --home /app app
WORKDIR /app

COPY --from=build /build/target/faultref-1.0.0.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENV DB_URL="jdbc:postgresql://db:5432/faultref" \
    DB_USER="faultref" \
    DB_PASSWORD="faultref" \
    APP_SECURITY_ENABLED="true" \
    APP_DEMO_ENABLED="true"

USER app
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
    CMD ["sh", "-c", "wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '\"status\":\"UP\"'"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
