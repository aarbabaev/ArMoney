# syntax=docker/dockerfile:1.7
FROM docker:29.8.1-cli AS dockercli
FROM eclipse-temurin:21.0.8_9-jdk-noble

ARG RUNNER_VERSION=2.338.0
# https://github.com/actions/runner/releases/tag/v2.338.0
ARG RUNNER_SHA256=af4b794c1bc41d73d40535e3fe092a39f9679cd8d965954c2aca25a05ca41d32
ARG ANDROID_TOOLS_VERSION=15859902
# https://developer.android.com/studio#command-line-tools-only
ARG ANDROID_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ARG ACCEPT_ANDROID_LICENSES=false

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk \
    PLAYWRIGHT_BROWSERS_PATH=/opt/playwright \
    RUNNER_ALLOW_RUNASROOT=1 \
    AGENT_TOOLSDIRECTORY=/opt/hostedtoolcache \
    PATH=/opt/venv/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/emulator:/opt/runner/externals/node24/bin:${PATH}

RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl git unzip zip jq python3 python3-venv sudo \
      libicu74 libssl3t64 libkrb5-3 zlib1g liblttng-ust1t64 \
      libgl1 libpulse0 libnss3 libx11-6 libxcb1 libxcomposite1 libxcursor1 \
      libxi6 libxtst6 libasound2t64 libxdamage1 libxrandr2 libxkbcommon0 \
      libxkbcommon-x11-0 libdbus-1-3 libfontconfig1 libfreetype6 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=dockercli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=dockercli /usr/local/libexec/docker/cli-plugins/ /usr/local/libexec/docker/cli-plugins/

RUN mkdir -p /opt/runner /opt/hostedtoolcache /work \
    && curl --fail --location --retry 3 \
      "https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/actions-runner-linux-x64-${RUNNER_VERSION}.tar.gz" -o /tmp/runner.tar.gz \
    && echo "${RUNNER_SHA256}  /tmp/runner.tar.gz" | sha256sum --check --strict \
    && tar -xzf /tmp/runner.tar.gz -C /opt/runner \
    && rm /tmp/runner.tar.gz

RUN python3 -m venv /opt/venv \
    && pip install --no-cache-dir playwright==1.55.0 \
    && python3 -m playwright install --with-deps chromium \
    && rm -rf /var/lib/apt/lists/*

RUN test "$ACCEPT_ANDROID_LICENSES" = true \
    && mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && curl --fail --location --retry 3 \
      "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_TOOLS_VERSION}_latest.zip" -o /tmp/android-tools.zip \
    && echo "${ANDROID_TOOLS_SHA256}  /tmp/android-tools.zip" | sha256sum --check --strict \
    && unzip -q /tmp/android-tools.zip -d "$ANDROID_HOME/cmdline-tools" \
    && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
    && rm /tmp/android-tools.zip \
    && (yes | sdkmanager --licenses >/dev/null 2>&1) \
    && sdkmanager 'platforms;android-36' 'build-tools;35.0.0' >/dev/null
# Fixed Android platform-tools and emulator archives from Google's repository2-1.xml.
# SHA-1 is the vendor's published archive checksum; transport uses HTTPS.
RUN curl --fail --location --retry 3 https://dl.google.com/android/repository/platform-tools_r37.0.1-linux.zip -o /tmp/platform-tools.zip \
    && echo '477254aa5f903c15cf51001717bdf347fb6b53e0  /tmp/platform-tools.zip' | sha1sum --check --strict \
    && unzip -q /tmp/platform-tools.zip -d "$ANDROID_HOME" \
    && curl --fail --location --retry 3 https://dl.google.com/android/repository/emulator-linux_x64-16428233.zip -o /tmp/emulator.zip \
    && echo 'cd7362ea55dfb86a418958138dc396e74165dd01  /tmp/emulator.zip' | sha1sum --check --strict \
    && unzip -q /tmp/emulator.zip -d "$ANDROID_HOME" \
    && rm /tmp/platform-tools.zip /tmp/emulator.zip

COPY --chmod=755 entrypoint.sh /usr/local/bin/armoney-runner
WORKDIR /opt/runner
ENTRYPOINT ["/usr/local/bin/armoney-runner"]
