package com.dataease.migration.operations.exec;

import static com.dataease.migration.operations.exec.HostCommands.*;

import com.dataease.migration.operations.media.DataEaseOfflinePackage;
import com.dataease.migration.operations.media.MediaService;

import java.nio.file.*;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Reuses, installs or recovers the local Docker runtime for offline deployment. */
final class OfflineDockerRuntime {
    private static final String HOME = "/opt/dataease-operations/docker";
    private static final String UNIT = "/etc/systemd/system/docker.service";
    private static final String UNIT_CONTENT = """
            [Unit]
            Description=Docker Engine installed from DataEase offline media
            Wants=network-online.target
            After=network-online.target

            [Service]
            Type=notify
            Environment=PATH=%s:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            ExecStart=%s/dockerd
            TimeoutStartSec=90
            Delegate=yes
            KillMode=process
            Restart=on-failure

            [Install]
            WantedBy=multi-user.target
            """.formatted(HOME, HOME);
    private static final String FRESH_CONFLICT = """
            for f in /usr/bin/docker /usr/bin/dockerd /usr/bin/docker-compose /usr/local/bin/docker \
                     /usr/local/bin/docker-compose /usr/sbin/dockerd /run/docker.sock /run/docker.pid \
                     /etc/docker/daemon.json /etc/systemd/system/docker.service \
                     /etc/systemd/system/docker.socket /usr/lib/systemd/system/docker.service \
                     /usr/lib/systemd/system/docker.socket /lib/systemd/system/docker.service \
                     /lib/systemd/system/docker.socket /opt/dataease-operations/docker; do
                if [ -e "$f" ] || [ -L "$f" ]; then printf 'conflict:%s' "$f"; exit; fi
            done
            for name in docker dockerd docker-compose; do
                if command -v "$name" >/dev/null 2>&1; then printf 'conflict:%s' "$name"; exit; fi
            done
            if [ -L /opt/dataease-operations ]; then printf 'conflict:/opt/dataease-operations'; exit; fi
            if [ -e /var/lib/docker ] || [ -L /var/lib/docker ]; then
                if [ -L /var/lib/docker ] || [ ! -d /var/lib/docker ] ||
                   ! contents=$(ls -A -- /var/lib/docker 2>/dev/null) || [ -n "$contents" ]; then
                    printf 'conflict:/var/lib/docker'
                fi
            fi
            """;
    private static final String MANAGED_LAYOUT = """
            test ! -e %1$s && test ! -L %1$s &&
            test ! -L /opt/dataease-operations &&
            test "$(readlink /usr/local/bin/docker)" = %1$s/docker &&
            test "$(readlink /usr/local/bin/docker-compose)" = %1$s/docker-compose &&
            test ! -L %2$s && printf '%%s' %3$s | cmp -s - %2$s
            """.formatted(HOME, UNIT, quote(UNIT_CONTENT));
    private static final String MANAGED_ACTIVE = """
            managed_pid() {
                [ -z "${DOCKER_HOST:-}${DOCKER_CONTEXT:-}" ] &&
                [ -S /run/docker.sock ] && [ ! -L /run/docker.sock ] &&
                [ "$(systemctl show -p FragmentPath --value docker.service)" = %1$s ] &&
                dropins=$(systemctl show -p DropInPaths --value docker.service) && [ -z "$dropins" ] &&
                systemctl is-active --quiet docker.service &&
                pid=$(systemctl show -p MainPID --value docker.service) || return 1
                case "$pid" in ''|0|*[!0-9]*) return 1;; esac
                [ "$(readlink "/proc/$pid/exe")" = "%2$s/dockerd (deleted)" ] || return 1
                child=$(pgrep -P "$pid" -x containerd) || return 1
                case "$child" in ''|0|*[!0-9]*) return 1;; esac
                [ "$(readlink "/proc/$child/exe")" = "%2$s/containerd (deleted)" ] || return 1
                dockerd_hash=$(sha256sum "/proc/$pid/exe") &&
                containerd_hash=$(sha256sum "/proc/$child/exe") || return 1
                [ "${dockerd_hash%%%% *}" = "$expected_dockerd" ] &&
                [ "${containerd_hash%%%% *}" = "$expected_containerd" ] || return 1
                printf '%%s' "$pid"
            }
            """.formatted(UNIT, HOME);
    private static final String LOCK = """
            set -eu
            exec 9>/run/lock/dataease-operations-docker.lock
            flock -n 9
            """;
    private final HostCommands host;

    enum Mode { REUSE, INSTALL, REPAIR }

    record Plan(String identity, String arch, Mode mode, String composeVersion) {
        boolean bootstrap() { return mode != Mode.REUSE; }

        String description() {
            return switch (mode) {
                case REUSE -> "将复用目标主机已有的 Docker 与 Compose " + composeVersion + "。";
                case INSTALL -> "目标主机未安装 Docker。确认后将从离线包安装主机共用的 Docker 与 Compose，卸载 DataEase 实例时会保留。";
                case REPAIR -> "Docker 服务仍在运行，Docker 与 Compose 命令文件缺失。确认安装后会从匹配的离线包补回文件，保留运行中的服务和数据。";
            };
        }
    }

    OfflineDockerRuntime(HostCommands host) {
        this.host = host;
    }

    Plan probe(Map<String, Object> i, Map<String, String> c, Map<String, Object> pkg) throws Exception {
        String[] system = host.capture(i, c, "uname -s && uname -m").split("\\R");
        if (system.length != 2) throw new IllegalArgumentException("无法识别目标系统和架构。");
        String arch = switch (system[1]) {
            case "x86_64", "amd64" -> "x86_64";
            case "aarch64", "arm64" -> "ARM64";
            default -> "unknown";
        };
        String runtime = host.capture(i, c, """
                if command -v docker >/dev/null 2>&1; then
                    endpoint=${DOCKER_HOST:-}
                    if [ -n "${DOCKER_CONTEXT:-}" ] || [ -z "$endpoint" ]; then
                        endpoint=$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null) ||
                            { printf docker-context-unavailable; exit; }
                    fi
                    case "$endpoint" in
                        unix://*)
                            if identity=$(docker info --format '{{.ID}}' 2>/dev/null) && [ -n "$identity" ]; then
                                printf '%s' "$identity"
                            else printf docker-unavailable; fi ;;
                        *) printf docker-context ;;
                    esac
                else printf docker-missing; fi
                """);
        if (runtime.equals("docker-context"))
            throw new IllegalArgumentException("请使用目标主机本机的 Docker Unix Socket，不能通过 Docker Context 指向另一台主机。");
        if (runtime.equals("docker-context-unavailable"))
            throw new IllegalArgumentException("找到了 Docker，但无法读取当前 Docker Context。请检查 Docker 命令和本机 Docker Context 配置。");
        if (!runtime.equals("docker-missing")) {
            if (runtime.isBlank() || runtime.equals("docker-unavailable"))
                throw new IllegalArgumentException("找到了 Docker，但当前账号无法访问 Docker 服务，请检查服务状态和权限。");
            String version = host.capture(i, c, COMPOSE + " version --short 2>/dev/null || printf compose-missing");
            if (!supportedCompose(version))
                throw new IllegalArgumentException("Docker 服务可用，但当前账号找不到可运行的 Compose V2 或更新版本。请为现有 Docker 安装或更新 Compose。");
            return new Plan(runtime, arch, Mode.REUSE, version);
        }
        boolean bundled = Boolean.TRUE.equals(pkg.get("bundledDocker"));
        if (bundled && (!"Linux".equals(system[0]) || !arch.equals(i.get("arch"))))
            throw new IllegalArgumentException("包内 Docker 只能安装在 CPU 架构匹配的 Linux 主机上。");
        String ready = host.capture(i, c, """
                %s
                if [ -n "${DOCKER_HOST:-}${DOCKER_CONTEXT:-}" ]; then printf context-configured
                elif [ "$(id -u)" != 0 ]; then printf root-required
                elif [ "$(cat /proc/1/comm)" != systemd ] ||
                     ! command -v systemctl >/dev/null 2>&1 ||
                     ! command -v flock >/dev/null 2>&1 ||
                     ! command -v iptables >/dev/null 2>&1; then printf system-required
                elif ( %s ); then
                    if repair_pid=$(managed_pid 2>/dev/null); then
                        printf 'repair:%%s:%%s' "$(sha256sum /etc/machine-id | cut -d' ' -f1)" "$repair_pid"
                    else printf repair-unverified; fi
                elif conflict=$( %s ) && [ -z "$conflict" ]; then
                    if test -s /etc/machine-id; then sha256sum /etc/machine-id | cut -d' ' -f1; fi
                else printf '%%s' "$conflict"; fi
                """.formatted(repairCheck(pkg), MANAGED_LAYOUT, FRESH_CONFLICT));
        if (ready.startsWith("conflict:"))
            throw new IllegalArgumentException("找不到可运行的 Docker 命令，检测到 " + ready.substring("conflict:".length())
                    + "。请在目标主机修复现有 Docker，确认 docker info 和 Compose 可用后重试。");
        if (ready.equals("repair-unverified"))
            throw new IllegalArgumentException("发现平台安装的 Docker 命令文件缺失，但无法核对当前 Docker 服务与所选离线包一致。请核对 Docker 服务状态，并选择包含原 Docker 运行时的离线包。");
        if (ready.equals("context-configured"))
            throw new IllegalArgumentException("找不到可运行的 Docker 命令，当前连接还设置了 DOCKER_HOST 或 DOCKER_CONTEXT。请清除该设置后重新检查本机 Docker。");
        if (!bundled)
            throw new IllegalArgumentException("目标主机找不到可运行的 Docker 命令，所选离线包也未提供完整且架构匹配的 Docker 和 Compose。");
        if (ready.equals("root-required"))
            throw new IllegalArgumentException("离线安装 Docker 需要使用目标主机的 root 账号连接。");
        if (ready.equals("system-required"))
            throw new IllegalArgumentException("离线安装 Docker 需要运行 systemd 的 Linux 主机，并提供 iptables 和 flock。");
        if (ready.matches("repair:[a-f0-9]{64}:[1-9][0-9]*"))
            return new Plan("machine:" + ready, arch, Mode.REPAIR, "");
        if (!ready.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("无法确认目标 Linux 主机身份。");
        return new Plan("machine:" + ready, arch, Mode.INSTALL, "");
    }

    private static boolean supportedCompose(String version) {
        return version.matches("v?(?:[2-9]|[1-9]\\d+)\\.\\d+\\.\\d+[^\\r\\n]*");
    }

    private static String repairCheck(Map<String, Object> pkg) {
        var files = pkg.get("files") instanceof Map<?, ?> manifest ? manifest : Map.of();
        return "expected_dockerd=" + quote(text(files, "docker/bin/dockerd", ""))
                + "\nexpected_containerd=" + quote(text(files, "docker/bin/containerd", ""))
                + "\n" + MANAGED_ACTIVE;
    }

    void install(Map<String, Object> pkg, Map<String, Object> i, Map<String, String> c,
            Plan expected, Consumer<String> log) throws Exception {
        Path content = Path.of(text(pkg, "contentRoot", ""));
        String stage = "/var/tmp/deops-docker-" + UUID.randomUUID();
        host.run(i, c, "umask 077 && mkdir " + quote(stage), line -> {});
        try {
            log.accept("正在传输并校验离线包内的 Docker 与 Compose 文件。");
            for (String name : DataEaseOfflinePackage.DOCKER_BINARIES) {
                Path binary = MediaService.safeChild(content, "docker/bin/" + name);
                String destination = stage + "/" + name;
                host.upload(i, c, binary, destination);
                String actual = host.capture(i, c, "sha256sum " + quote(destination)).split("\\s+")[0];
                if (!(pkg.get("files") instanceof Map<?, ?> files)
                        || !actual.equals(files.get("docker/bin/" + name)))
                    throw new IllegalStateException("包内 Docker 文件传输校验失败。");
            }
            if (!expected.equals(probe(i, c, pkg)))
                throw new IllegalStateException("目标 Docker 环境已变化，请重新预检查。");
            host.run(i, c, "chmod 0700 " + quote(stage + "/docker") + " " + quote(stage + "/docker-compose"), line -> {});
            String stagedCompose = host.capture(i, c, quote(stage + "/docker-compose") + " version --short");
            if (!supportedCompose(stagedCompose))
                throw new IllegalStateException("离线包内的 Compose 无法运行或版本低于 V2。");
            boolean repair = expected.mode() == Mode.REPAIR;
            log.accept(repair ? "正在补回 Docker 与 Compose 命令文件。" : "正在安装并启动 Docker 服务。");
            String activate = repair ? repairCommand(stage, pkg, expected) : LOCK + """
                    conflict=$( %s )
                    test -z "$conflict"
                    test "$(sha256sum /etc/machine-id | cut -d' ' -f1)" = %s
                    install -d -m 0755 %s /usr/local/bin
                    install -m 0755 %s/* %s/
                    ln -s %s/docker /usr/local/bin/docker
                    ln -s %s/docker-compose /usr/local/bin/docker-compose
                    umask 022
                    set -C
                    printf '%%s' %s > %s
                    systemctl daemon-reload
                    systemctl enable --now docker.service
                    """.formatted(FRESH_CONFLICT, quote(expected.identity().substring("machine:".length())),
                            HOME, quote(stage), HOME, HOME, HOME, quote(UNIT_CONTENT), UNIT);
            try {
                host.run(i, c, activate, line -> {});
            } catch (IllegalStateException e) {
                throw new IllegalStateException(repair
                        ? "补回 Docker 命令文件失败，请重新检查目标机 Docker 状态。"
                        : "包内 Docker 安装或启动失败，请在目标机查看 systemctl status docker 和 journalctl -u docker。", e);
            }
            Plan installed = probe(i, c, Map.of());
            if (installed.bootstrap() || !installed.arch().equals(expected.arch()))
                throw new IllegalStateException("Docker 或 Compose 未通过可用性核验。");
            log.accept((repair ? "已补回 Docker 与 Compose 命令文件，复用原有 Docker 服务和数据。"
                    : "已从离线包安装并启动 Docker。") + "已核验 Compose " + installed.composeVersion() + "。");
        } finally {
            try { host.run(i, c, "rm -rf -- " + quote(stage), line -> {}); }
            catch (Exception ignored) { /* A failed staging cleanup does not change Docker state. */ }
        }
    }

    private static String repairCommand(String stage, Map<String, Object> pkg, Plan expected) {
        return LOCK + repairCheck(pkg) + """

                ( %s )
                repair_pid=$(managed_pid)
                test "machine:repair:$(sha256sum /etc/machine-id | cut -d' ' -f1):$repair_pid" = %s
                %s --host unix:///run/docker.sock info --format '{{.ID}}' >/dev/null
                mkdir -p /opt/dataease-operations
                replacement=$(mktemp -d /opt/dataease-operations/.docker-recovery.XXXXXXXX)
                trap 'rm -rf -- "$replacement"' EXIT
                install -m 0755 %s/* "$replacement/"
                chmod 0755 "$replacement"
                mv -Tn -- "$replacement" %s
                test ! -d "$replacement"
                """.formatted(MANAGED_LAYOUT, quote(expected.identity()), quote(stage + "/docker"), quote(stage), HOME);
    }
}
