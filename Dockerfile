FROM scratch AS tools
COPY tools/mysql/linux-arm64 /tools/mysql/linux-arm64
COPY tools/mysql/linux-x64 /tools/mysql/linux-x64

FROM registry.cn-qingdao.aliyuncs.com/dataease/alpine-openjdk21-jre
STOPSIGNAL SIGTERM

WORKDIR /opt/apps

ADD target/dataease-migration-1.0.0.jar /opt/apps/app.jar
COPY --from=tools /tools/mysql /opt/apps/tools/mysql

ENV JAVA_APP_JAR=/opt/apps/app.jar
ENV MIGRATION_COPY_SYNC_TASK_LOGS=false
ENV MIGRATION_MYSQL_TOOLS_DIRECTORY=/opt/apps/tools/mysql

HEALTHCHECK --interval=15s --timeout=5s --retries=20 --start-period=30s CMD nc -zv 127.0.0.1 8080

CMD ["/deployments/run-java.sh"]
