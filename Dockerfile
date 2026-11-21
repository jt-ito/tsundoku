# syntax=docker/dockerfile:1.7
#
# tsundoku: builds the server jar and the tsundoku-WebUI, and runs them together.
#
#   docker build -t tsundoku .
#   docker run -p 4567:4567 -v tsundoku-data:/data tsundoku
#
# The WebUI (branch master of tsundoku-WebUI) is cloned and built here. Pick another repository or branch with
#   --build-arg WEBUI_REPO=https://github.com/you/your-webui.git --build-arg WEBUI_REF=my-branch
# The build context must contain .git: the version number is the commit count.

ARG WEBUI_REPO=https://github.com/jt-ito/tsundoku-WebUI.git
ARG WEBUI_REF=master

# ---------------------------------------------------------------- WebUI
FROM node:24-bookworm-slim AS webui
ARG WEBUI_REPO
ARG WEBUI_REF
RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /webui
# The build cache keys a step by its text, not by what the repository holds now, so a cached clone kept an old WebUI in
# every image. The CI passes the current commit of the WebUI here, a different value runs the clone and build again
# (a local build can do the same with --build-arg WEBUI_COMMIT=$(date +%s) or --no-cache-filter webui).
ARG WEBUI_COMMIT=""
RUN echo "WebUI commit: ${WEBUI_COMMIT:-unknown}" \
    && git clone ${WEBUI_REF:+--branch "$WEBUI_REF"} "$WEBUI_REPO" .
ENV CI=true HUSKY=0
RUN corepack enable && pnpm build \
    # the server reads the version of a custom WebUI from this file (r<number>) and shows the splash screen without it;
    # the number is the count of commits of the WebUI, so the build in an image can be told from another one
    && echo "r$(git rev-list --count HEAD)" > build/revision

# ---------------------------------------------------------------- server jar
FROM eclipse-temurin:21-jdk-noble AS server
RUN apt-get update \
    && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :server:shadowJar --no-daemon -Dorg.gradle.jvmargs=-Xmx3g \
    && mkdir -p /out \
    && cp "$(ls server/build/*.jar | head -n 1)" /out/tsundoku.jar

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre-noble

# The libraries and xvfb (a virtual display) are for the WebView, a Chromium that runs on the server and refuses to start
# without a display. The server works without the WebView if they are missing.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        curl tzdata ca-certificates xvfb \
        libnss3 libnspr4 libatk1.0-0t64 libatk-bridge2.0-0t64 libcups2t64 libdrm2 libgbm1 \
        libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libxkbcommon0 libx11-6 libxcb1 libxext6 libxi6 libxtst6 \
        libpango-1.0-0 libcairo2 libasound2t64 libdbus-1-3 libglib2.0-0t64 libgtk-3-0t64 \
    && rm -rf /var/lib/apt/lists/* \
    # the server runs as an unprivileged user (the built-in PostgreSQL refuses to start as root)
    && (userdel -r ubuntu 2>/dev/null || true) \
    && groupadd -g 1000 tsundoku \
    && useradd -u 1000 -g 1000 -m -d /home/tsundoku -s /usr/sbin/nologin tsundoku \
    && mkdir -p /data /opt/tsundoku \
    && mkdir -m 1777 /tmp/.X11-unix \
    && chown tsundoku:tsundoku /data

COPY --from=server --chown=tsundoku:tsundoku /out/tsundoku.jar /opt/tsundoku/tsundoku.jar
COPY --from=webui --chown=tsundoku:tsundoku /webui/build /opt/tsundoku/webUI
COPY --chown=tsundoku:tsundoku docker-entrypoint.sh /opt/tsundoku/docker-entrypoint.sh
RUN chmod +x /opt/tsundoku/docker-entrypoint.sh

ENV TZ=Etc/UTC \
    BIND_IP=0.0.0.0 \
    BIND_PORT=4567 \
    WEB_UI_FLAVOR=CUSTOM \
    JAVA_OPTS=""

LABEL org.opencontainers.image.title="tsundoku" \
      org.opencontainers.image.description="Self-hosted manga reader server (a fork of Suwayomi-Server)" \
      org.opencontainers.image.source="https://github.com/jt-ito/tsundoku" \
      org.opencontainers.image.licenses="MPL-2.0"

# starts as root only to fix the ownership of /data and /data/downloads, then runs as tsundoku (see the entrypoint)
WORKDIR /data
VOLUME /data
EXPOSE 4567

# login.html is the one page that is reachable without logging in
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -fsS "http://localhost:${BIND_PORT}/login.html" > /dev/null || exit 1

ENTRYPOINT ["/opt/tsundoku/docker-entrypoint.sh"]
