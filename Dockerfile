FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
ARG SERVICE
RUN chmod +x gradlew && ./gradlew --no-daemon :${SERVICE}:installDist && mv ${SERVICE}/build/install/${SERVICE} /opt/service

FROM eclipse-temurin:21-jre
RUN groupadd --system bank && useradd --system --gid bank bank
WORKDIR /opt/service
COPY --from=build --chown=bank:bank /opt/service/ ./
USER bank
EXPOSE 8080
ENTRYPOINT ["/bin/sh", "-c", "exec /opt/service/bin/*"]
