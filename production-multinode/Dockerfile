# Build dependencies are resolved in a reproducible JDK/Maven image; the runtime image contains only the JRE.
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN useradd --system --uid 10001 --create-home appuser
WORKDIR /app
COPY --from=build /workspace/target/transaction-service-production.jar /app/transaction-service.jar
USER 10001:10001
EXPOSE 8080
ENV PORT=8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/transaction-service.jar"]
