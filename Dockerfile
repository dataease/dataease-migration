ARG JAVA_BASE_IMAGE=registry.cn-qingdao.aliyuncs.com/dataease/alpine-openjdk21-jre

FROM --platform=$BUILDPLATFORM node:22-bookworm-slim AS frontend-build
WORKDIR /build/frontend

COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --ignore-scripts --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM --platform=$BUILDPLATFORM ${JAVA_BASE_IMAGE} AS application-build
ARG MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/central
WORKDIR /build

RUN apk add --no-cache openjdk21-jdk maven
COPY pom.xml ./
COPY docker/maven-settings.xml /tmp/maven-settings.xml
COPY src/ src/
COPY --from=frontend-build /build/target/generated-resources/operations/static/ target/generated-resources/operations/static/
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked \
    mvn -s /tmp/maven-settings.xml -B -ntp -Dfrontend.skip=true -Dassembly.skipAssembly=true package

FROM ${JAVA_BASE_IMAGE}
ARG APP_VERSION=1.1
LABEL org.opencontainers.image.version="v${APP_VERSION}"
STOPSIGNAL SIGTERM

WORKDIR /opt/apps

RUN apk add --no-cache bash tar docker-cli docker-cli-compose

COPY --from=application-build /build/target/dataease-migration-${APP_VERSION}.jar /opt/apps/app.jar
COPY plugins /opt/apps/plugins

ENV JAVA_APP_JAR=/opt/apps/app.jar
ENV JAVA_APP_DIR=/opt/apps
ENV MIGRATION_PLUGINS_DIRECTORY=/opt/apps/plugins
ENV MIGRATION_COPY_SYNC_TASK_LOGS=false
ENV OPERATIONS_DATA_DIRECTORY=/opt/apps/data/operations

VOLUME ["/opt/apps/data/operations"]

HEALTHCHECK --interval=15s --timeout=5s --retries=20 --start-period=30s CMD nc -zv 127.0.0.1 8080

CMD ["/deployments/run-java.sh"]
