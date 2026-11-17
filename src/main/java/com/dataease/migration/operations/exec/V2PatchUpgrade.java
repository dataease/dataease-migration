package com.dataease.migration.operations.exec;

import java.util.Map;
import java.util.function.Consumer;

import static com.dataease.migration.operations.exec.HostCommands.path;
import static com.dataease.migration.operations.exec.HostCommands.quote;
import static com.dataease.migration.operations.exec.HostCommands.text;

/** Protects the old MySQL data dictionary while applying the V2 maintenance release. */
final class V2PatchUpgrade {
    private static final String RECOVERY = ".ops-v2-patch";
    private static final String DATA = "data/mysql";
    private final HostCommands host;
    private final ComposeManager compose;
    private final DatabaseArchive database;

    V2PatchUpgrade(HostCommands host, ComposeManager compose, DatabaseArchive database) {
        this.host = host;
        this.compose = compose;
        this.database = database;
    }

    static String recoveryPath(Map<String, Object> i) {
        return path(text(i, "path", "")) + "/" + RECOVERY;
    }

    private String workingDirectory(Map<String, Object> i) {
        return "set -eu; cd " + quote(path(text(i, "path", ""))) + "\n";
    }

    void verifySource(Map<String, Object> i, Map<String, String> c) throws Exception {
        var live = mysql(i, c);
        String data = path(text(i, "path", "")) + "/" + DATA;
        if (!(live.get("Mounts") instanceof java.util.List<?> mounts)
                || mounts.stream().filter(value -> value instanceof Map<?, ?> m
                        && "/var/lib/mysql".equals(m.get("Destination"))).count() != 1
                || mounts.stream().noneMatch(value -> value instanceof Map<?, ?> m
                        && "/var/lib/mysql".equals(m.get("Destination")) && data.equals(m.get("Source"))
                        && "bind".equals(m.get("Type")) && Boolean.TRUE.equals(m.get("RW"))))
            throw new IllegalArgumentException("MySQL 数据卷与原厂单机部署不一致，不能建立旧数据目录副本。");
        String status = host.capture(i, c, workingDirectory(i) + """
                test ! -e .ops-v2-patch && test ! -L .ops-v2-patch || { printf pending; exit; }
                for f in .env conf/application.yml; do test -f "$f" && test ! -L "$f"; done
                test -d data/mysql && test "$(realpath -- data/mysql)" = "$PWD/data/mysql"
                test "$(stat -c %d -- .)" = "$(stat -c %d -- data/mysql)"
                if grep -Eq '^DE_SYNC_AUTH_(ENABLED|SECRET|VERSION)=' .env; then
                    printf custom; exit
                fi
                if grep -Eq '^    auth:[[:space:]]*$' conf/application.yml; then
                    printf custom; exit
                fi
                printf ready
                """);
        if ("pending".equals(status))
            throw new IllegalArgumentException("此实例已有一份 V2 升级回退记录，请先处理原升级任务。");
        if (!"ready".equals(status))
            throw new IllegalArgumentException("原 V2 使用了自定义同步鉴权配置，需按原厂指导核对后升级。");
        checkMysqlDataLinks(i, c);
    }

    private void checkMysqlDataLinks(Map<String, Object> i, Map<String, String> c) throws Exception {
        // The stock MySQL image keeps this runtime socket link even after a clean shutdown.
        String unsafe = host.capture(i, c, workingDirectory(i) + """
                find data/mysql -type l ! \\( -path data/mysql/mysql.sock -lname /var/run/mysqld/mysqld.sock \\) -print -quit
                """);
        if (!unsafe.isBlank())
            throw new IllegalArgumentException("MySQL 数据目录存在原厂套接字之外的符号链接，无法安全复制。");
    }

    void stage(Map<String, Object> i, Map<String, String> c, Consumer<String> log) throws Exception {
        compose.verifyStopped(i, c);
        verifySource(i, c);
        host.run(i, c, workingDirectory(i) + """
                umask 077
                mkdir .ops-v2-patch
                cp -a -- .env .ops-v2-patch/env.before
                cp -a -- conf/application.yml .ops-v2-patch/application.before
                if test -e .ops-compose-release.yml || test -L .ops-compose-release.yml; then
                    test -f .ops-compose-release.yml && test ! -L .ops-compose-release.yml
                    cp -a -- .ops-compose-release.yml .ops-v2-patch/release.before
                fi
                cp -a -- .env .ops-v2-patch/env.next
                printf '\\nDE_SYNC_AUTH_ENABLED=false\\n' >> .ops-v2-patch/env.next
                awk '
                    /^task:[[:space:]]*$/ { within=1; print; next }
                    /^[^[:space:]#]/ { within=0 }
                    within && /^  executor:[[:space:]]*$/ {
                        print; print "    auth:"; print "      secret: ${DE_SYNC_AUTH_SECRET:}"; added++; next
                    }
                    { print }
                    END { if (added != 1) exit 1 }
                ' conf/application.yml > .ops-v2-patch/application.next
                test ! -L data/report && test ! -L data/report-artifacts
                mkdir -p data/report data/report-artifacts
                touch .ops-v2-patch/config-saved
                """, line -> {});
        log.accept("已保存原部署配置，升级后同步鉴权沿用离线包默认值。");
    }

    void activate(Map<String, Object> i, Map<String, String> c, Consumer<String> log) throws Exception {
        try (var connection = database.connect(i, c); var statement = connection.createStatement()) {
            statement.setQueryTimeout(15);
            statement.execute("SET GLOBAL innodb_fast_shutdown=0");
        }
        stopMysql(i, c);
        log.accept("内置 MySQL 已正常停止，正在保留升级前的数据目录并复制升级副本。");
        checkMysqlDataLinks(i, c);
        try {
            host.run(i, c, workingDirectory(i) + """
                    test -f .ops-v2-patch/config-saved
                    test -s .ops-v2-patch/release.next
                    cp -a --reflink=auto -- data/mysql .ops-v2-patch/mysql-next
                    """, line -> {});
        } catch (IllegalStateException failure) {
            if (HostCommands.uncertainCompletion(failure)) throw failure;
            throw new IllegalStateException("复制旧 MySQL 数据目录失败，请核对目标磁盘余量、目录权限和回退文件。", failure);
        }
        log.accept("旧 MySQL 数据目录已复制，正在切换数据目录和部署配置。");
        host.run(i, c, workingDirectory(i) + """
                mv -T -- data/mysql .ops-v2-patch/mysql-original
                mv -T -- .ops-v2-patch/mysql-next data/mysql
                mv -fT -- .ops-v2-patch/env.next .env
                mv -fT -- .ops-v2-patch/application.next conf/application.yml
                mv -fT -- .ops-v2-patch/release.next .ops-compose-release.yml
                """, line -> {});
    }

    void restore(Map<String, Object> i, Map<String, String> c, Consumer<String> log) throws Exception {
        compose.verifyStopped(i, c);
        String journal = host.capture(i, c, workingDirectory(i) + """
                if test -d .ops-v2-patch && test ! -L .ops-v2-patch; then
                    printf present
                fi
                """);
        if (journal.isEmpty()) return;
        if (!host.capture(i, c, workingDirectory(i)
                + "if test -d .ops-v2-patch/mysql-original; then printf present; fi").isEmpty())
            stopMysql(i, c);
        host.run(i, c, workingDirectory(i) + """
                if test -f .ops-v2-patch/config-saved; then
                    cp -a -- .ops-v2-patch/env.before .ops-v2-patch/env.restore
                    cp -a -- .ops-v2-patch/application.before .ops-v2-patch/application.restore
                    mv -fT -- .ops-v2-patch/env.restore .env
                    mv -fT -- .ops-v2-patch/application.restore conf/application.yml
                    if test -f .ops-v2-patch/release.before; then
                        cp -a -- .ops-v2-patch/release.before .ops-v2-patch/release.restore
                        mv -fT -- .ops-v2-patch/release.restore .ops-compose-release.yml
                    else
                        rm -f -- .ops-compose-release.yml
                    fi
                fi
                if test -d .ops-v2-patch/mysql-original; then
                    if test -d data/mysql; then
                        mv -T -- data/mysql .ops-v2-patch/mysql-upgraded
                    fi
                    mv -T -- .ops-v2-patch/mysql-original data/mysql
                fi
                test -d data/mysql
                """, line -> {});
        log.accept("已切回原 MySQL 数据目录及原厂部署配置。");
    }

    void removeRecoveredJournal(Map<String, Object> i, Map<String, String> c) throws Exception {
        host.run(i, c, workingDirectory(i) + """
                test ! -L .ops-v2-patch && test ! -e .ops-v2-patch/mysql-original
                rm -rf -- .ops-v2-patch
                """, line -> {});
    }

    private Map<String, Object> mysql(Map<String, Object> i, Map<String, String> c) throws Exception {
        String id = compose.containerId(i, c, text(i, "databaseService", ""));
        if (!id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("无法核对内置 MySQL 容器。");
        var live = compose.container(i, c, id);
        if (!(live.get("Config") instanceof Map<?, ?> config) || !(config.get("Labels") instanceof Map<?, ?> labels)
                || !i.get("path").equals(labels.get("com.docker.compose.project.working_dir"))
                || !i.get("composeProject").equals(labels.get("com.docker.compose.project"))
                || !i.get("databaseService").equals(labels.get("com.docker.compose.service")))
            throw new IllegalArgumentException("内置 MySQL 容器归属与实例记录不一致。");
        return live;
    }

    private void stopMysql(Map<String, Object> i, Map<String, String> c) throws Exception {
        host.run(i, c, compose.command(i) + " stop -t 180 " + quote(text(i, "databaseService", "")), line -> {});
        if (!(mysql(i, c).get("State") instanceof Map<?, ?> state)
                || !Boolean.FALSE.equals(state.get("Running"))
                || (state.get("ExitCode") instanceof Number code && code.intValue() == 137))
            throw new IllegalStateException("内置 MySQL 的停止结果不确定，已保留原数据目录。");
    }
}
