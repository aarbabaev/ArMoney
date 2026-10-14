FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon :sms-sandbox:installDist

FROM eclipse-temurin:21-jre
RUN groupadd --system bank && useradd --system --gid bank bank && mkdir /data && chown bank:bank /data && chmod 700 /data
WORKDIR /opt/service
COPY --from=build --chown=bank:bank /workspace/sms-sandbox/build/install/sms-sandbox/ ./
COPY infra/runtime/java-security.properties /opt/service/java-security.properties
ENV JAVA_TOOL_OPTIONS="-Djava.security.properties=/opt/service/java-security.properties"
USER bank
EXPOSE 8080
ENTRYPOINT ["/opt/service/bin/sms-sandbox"]
