package com.dataease.migration.operations.exec;

import static com.dataease.migration.operations.exec.HostCommands.quote;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Adds simultaneous allocations per filesystem and keeps the highest demand across phases. */
final class UpgradeCapacity {
    static final long MIB = 1024L * 1024;
    static final long RESERVE = 512 * MIB;
    private static final long UNSPECIFIED_IMAGE_RESERVE = 10L * 1024 * MIB;
    private static final String MOUNT = "stat -L -c %d -- ";
    private static final String FREE = "df -Pk -- ";

    record Demand(String directory, String label, long bytes) {}
    record Mount(String device, long free, String label) {}

    private UpgradeCapacity() {}

    static long dockerLoadEstimate(Object measured, long archiveBytes) {
        // Existing uploads retain their seals. Reverification provides an exact archive measurement.
        return measured instanceof Number bytes && bytes.longValue() > 0
                ? bytes.longValue() : Math.max(UNSPECIFIED_IMAGE_RESERVE, Math.multiplyExact(archiveBytes, 8));
    }

    static Mount mount(HostCommands host, Map<String, Object> instance, Map<String, String> credentials,
            Demand demand) throws Exception {
        String directory = quote(demand.directory());
        String[] values = host.capture(instance, credentials,
                MOUNT + directory + " && " + FREE + directory + " | awk 'NR == 2 {print $4}'").lines()
                .toArray(String[]::new);
        if (values.length != 2 || !values[0].matches("[0-9]+") || !values[1].matches("[0-9]+"))
            throw new IllegalArgumentException("无法读取" + demand.label() + "的实际可用空间。");
        return new Mount(values[0], Math.multiplyExact(Long.parseLong(values[1]), 1024), demand.label());
    }

    static List<Demand> dockerDemand(HostCommands host, Map<String, Object> instance,
            Map<String, String> credentials, long bytes) throws Exception {
        if (bytes == 0) return List.of();
        String root = HostCommands.path(host.capture(instance, credentials, "docker info --format '{{.DockerRootDir}}'"));
        var primary = new Demand(root, "目标 Docker 镜像盘", bytes);
        String driver = host.capture(instance, credentials, "docker info --format '{{.Driver}}'").strip();
        if ("overlayfs".equals(driver)) {
            String containerd = host.capture(instance, credentials, """
                    for config in /run/docker/containerd/containerd.toml /var/run/docker/containerd/containerd.toml; do
                        if [ -r "$config" ]; then
                            sed -n 's/^root[[:space:]]*=[[:space:]]*//p' "$config" | head -n 1
                            break
                        fi
                    done
                    """);
            String imageRoot = containerd.isBlank() ? "/var/lib/containerd"
                    : HostCommands.path(containerd.strip().replaceAll("^(['\"])(.*)\\1$", "$2"));
            if ("yes".equals(host.capture(instance, credentials, "test ! -d " + quote(imageRoot) + " || printf yes"))) {
                var content = new Demand(imageRoot, "目标 Docker 镜像盘", bytes);
                if (!mount(host, instance, credentials, primary).device()
                        .equals(mount(host, instance, credentials, content).device()))
                    return List.of(primary, content);
            } else if (!containerd.isBlank()) {
                throw new IllegalArgumentException("无法核对目标 Docker 镜像盘。");
            }
        }
        return List.of(primary);
    }

    static String requireSpace(HostCommands host, Map<String, Object> instance, Map<String, String> credentials,
            Path repository, long repositoryBytes,
            List<Demand> backup, List<Demand> images, List<Demand> rollback) throws Exception {
        var mounts = new HashMap<String, Mount>();
        var labels = new LinkedHashMap<String, Set<String>>();
        var free = new HashMap<String, Long>();
        var peak = new LinkedHashMap<String, Long>();
        // A loopback connection or an SSH connection back into the same host can share
        // a filesystem with the operations container's backup volume.
        boolean sameHost = com.dataease.migration.service.MigrationService.isLocalServer(host.server(instance, credentials));
        Path bootId = Path.of("/proc/sys/kernel/random/boot_id");
        if (!sameHost && Files.isRegularFile(bootId)) {
            String targetBoot = host.capture(instance, credentials, "cat /proc/sys/kernel/random/boot_id");
            sameHost = !targetBoot.isBlank() && targetBoot.equals(Files.readString(bootId).strip());
        }
        String repositoryLabel = "运维中心备份库";
        String repositoryDevice = sameHost ? Long.toUnsignedString(((Number) Files.getAttribute(repository, "unix:dev")).longValue())
                : "repository";
        labels.put(repositoryDevice, new LinkedHashSet<>(Set.of(repositoryLabel)));
        free.put(repositoryDevice, Files.getFileStore(repository).getUsableSpace());
        for (var phase : List.of(backup, images, rollback)) {
            var totals = new LinkedHashMap<String, Long>();
            totals.put(repositoryDevice, repositoryBytes);
            for (var demand : phase) {
                if (demand.bytes() <= 0) continue;
                Mount measured = mounts.get(demand.directory());
                if (measured == null) {
                    measured = mount(host, instance, credentials, demand);
                    mounts.put(demand.directory(), measured);
                }
                String device = measured.device();
                labels.computeIfAbsent(device, ignored -> new LinkedHashSet<>()).add(demand.label());
                free.merge(device, measured.free(), Math::min);
                totals.merge(device, demand.bytes(), Math::addExact);
            }
            totals.forEach((device, bytes) -> peak.merge(device, bytes, Math::max));
        }
        var details = new ArrayList<String>();
        for (var entry : peak.entrySet())
            details.add(require(String.join(" / ", labels.get(entry.getKey())),
                    free.get(entry.getKey()), Math.addExact(entry.getValue(), RESERVE)));
        return String.join("；", details);
    }

    static String requireLocal(Path path, String label, long required) throws Exception {
        return require(label, Files.getFileStore(path).getUsableSpace(), Math.addExact(required, RESERVE));
    }

    static String require(String label, long available, long required) {
        String result = label + "需 " + mib(required, true) + " MiB，可用 " + mib(available, false) + " MiB";
        if (available < required)
            throw new IllegalArgumentException(result + "，还差 " + mib(required - available, true)
                    + " MiB。释放空间后重新检查。");
        return result;
    }

    private static long mib(long bytes, boolean roundUp) {
        return bytes / MIB + (roundUp && bytes % MIB != 0 ? 1 : 0);
    }
}
