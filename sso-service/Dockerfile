# Build from the repository root. Source packaging only; building/running needs the orchestrator's lease.
# Digests were read from the official registries without pulling image layers.
FROM gradle:8.14.3-jdk21@sha256:21bd311ed01360c189b8870c6b6e988199ff10f72d445d02fb39d3cff9da91d7 AS provider-build
USER root
WORKDIR /workspace
COPY gradlew ./gradlew
COPY gradle/wrapper/ ./gradle/wrapper/
COPY sso-service/Dockerfile ./sso-service/Dockerfile
COPY sso-service/provider/build.gradle sso-service/provider/settings.gradle sso-service/provider/package-provider.sh ./sso-service/provider/
COPY sso-service/provider/src/ ./sso-service/provider/src/
COPY sso-service/themes/armoney/login/ ./sso-service/themes/armoney/login/
COPY sso-service/themes/armoney/email/ ./sso-service/themes/armoney/email/
RUN bash sso-service/provider/package-provider.sh /provider-package

FROM quay.io/keycloak/keycloak:26.7.4@sha256:82a77884f3af238beab1e7afd63b5f530e1b5c0590bd7aa60b40a40463e29b2c AS keycloak-build
ARG ARMONEY_EMAIL_SENDER_PROVIDER=default
ENV KC_HTTP_RELATIVE_PATH=/sso
COPY --from=provider-build --chown=keycloak:keycloak /provider-package/providers/ /opt/keycloak/providers/
COPY --from=provider-build --chown=keycloak:keycloak /provider-package/themes/ /opt/keycloak/themes/
# Keycloak augmentation discovers existing SPI implementations; no server/database or realm import runs.
RUN touch -m --date=@1743465600 /opt/keycloak/providers/armoney-keycloak-provider.jar \
    && case "$ARMONEY_EMAIL_SENDER_PROVIDER" in default|armoney-strict-mailtrap) ;; *) exit 1 ;; esac \
    && /opt/keycloak/bin/kc.sh build --db=postgres --spi-email-sender--provider-default="$ARMONEY_EMAIL_SENDER_PROVIDER"

FROM quay.io/keycloak/keycloak:26.7.4@sha256:82a77884f3af238beab1e7afd63b5f530e1b5c0590bd7aa60b40a40463e29b2c
ARG ARMONEY_EMAIL_SENDER_PROVIDER=default
ENV KC_HTTP_RELATIVE_PATH=/sso
COPY --from=keycloak-build --chown=keycloak:keycloak /opt/keycloak/ /opt/keycloak/
COPY --from=provider-build --chown=keycloak:keycloak /provider-package/provenance/ /opt/keycloak/conf/armoney-provider-package/
LABEL org.opencontainers.image.title="ArMoney Keycloak provider" \
      io.armoney.email-sender="$ARMONEY_EMAIL_SENDER_PROVIDER" \
      org.opencontainers.image.base.name="quay.io/keycloak/keycloak:26.7.4" \
      org.opencontainers.image.base.digest="sha256:82a77884f3af238beab1e7afd63b5f530e1b5c0590bd7aa60b40a40463e29b2c"
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
# Supply start --optimized and normal operator configuration deliberately. No realm/theme/flow is activated here.
