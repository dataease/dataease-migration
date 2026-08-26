FROM registry.cn-qingdao.aliyuncs.com/dataease/alpine-openjdk21-jre AS tools
ARG TARGETARCH
WORKDIR /staging
COPY tools/mysql /tools-src/mysql
RUN case "${TARGETARCH}" in \
        amd64) dir=linux-x64 ;; \
        arm64) dir=linux-arm64 ;; \
        *) echo "不支持的架构：${TARGETARCH}" >&2; exit 1 ;; \
    esac \
    && mkdir -p /staging/tools/mysql \
    && cp -a "/tools-src/mysql/${dir}" "/staging/tools/mysql/${dir}"

FROM registry.cn-qingdao.aliyuncs.com/dataease/alpine-openjdk21-jre
STOPSIGNAL SIGTERM

WORKDIR /opt/apps

ADD target/dataease-migration-1.0.0.jar /opt/apps/app.jar
COPY --from=tools /staging/tools/mysql /opt/apps/tools/mysql

ENV JAVA_APP_JAR=/opt/apps/app.jar
ENV MIGRATION_COPY_SYNC_TASK_LOGS=false
ENV MIGRATION_MYSQL_TOOLS_DIRECTORY=/opt/apps/tools/mysql

HEALTHCHECK --interval=15s --timeout=5s --retries=20 --start-period=30s CMD nc -zv 127.0.0.1 8080

CMD ["/deployments/run-java.sh"]
