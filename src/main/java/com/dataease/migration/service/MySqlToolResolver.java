package com.dataease.migration.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class MySqlToolResolver {
    private final Path toolsDirectory;

    public MySqlToolResolver(@Value("${migration.mysql-tools.directory:tools/mysql}") String toolsDirectory) {
        this.toolsDirectory = Path.of(toolsDirectory);
    }

    public Optional<MySqlTools> resolve() {
        String suffix = isWindows() ? ".exe" : "";
        for (String platform : platformCandidates()) {
            Path binDirectory = toolsDirectory.resolve(platform).resolve("bin");
            Path mysql = binDirectory.resolve("mysql" + suffix);
            Path mysqldump = binDirectory.resolve("mysqldump" + suffix);
            if (Files.isRegularFile(mysql) && Files.isExecutable(mysql)
                    && Files.isRegularFile(mysqldump) && Files.isExecutable(mysqldump)) {
                return Optional.of(new MySqlTools(mysql, mysqldump, platform));
            }
        }
        return Optional.empty();
    }

    public String platform() {
        return platformCandidates().get(0);
    }

    private List<String> platformCandidates() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String normalizedArch = switch (arch) {
            case "aarch64", "arm64" -> "arm64";
            case "x86_64", "amd64" -> "x64";
            default -> arch;
        };
        if (os.contains("mac") || os.contains("darwin")) {
            return List.of("macos-" + normalizedArch);
        }
        if (os.contains("win")) {
            if ("arm64".equals(normalizedArch)) {
                return List.of("windows-arm64", "windows-x64");
            }
            return List.of("windows-" + normalizedArch);
        }
        if (os.contains("linux")) {
            return List.of("linux-" + normalizedArch);
        }
        return List.of("unsupported-" + normalizedArch);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public record MySqlTools(Path mysql, Path mysqldump, String platform) {
    }
}
