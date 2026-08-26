package com.dataease.migration.service;

import com.dataease.migration.model.ServerInfo;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 校验 DataEase 2.0 源端版本是否满足迁移工具的最低要求。
 *
 * <p>版本读取自安装目录下的 docker-compose.yml：定位 {@code dataease} 服务块中的 {@code image}，
 * 再取镜像最后一个冒号之后的 tag。tag 为 {@code dev-v2}，或解析为 {@code v2.10.26} 及以上版本时通过。</p>
 */
@Component
public class DataEaseVersionValidator {
    private static final String COMPOSE_FILE_NAME = "docker-compose.yml";
    private static final String MINIMUM_TAG = "v2.10.26";
    private static final String DEV_TAG = "dev-v2";
    private static final String UNSUPPORTED_MESSAGE = "dataease最低版本为v2.10.26";
    private static final Pattern VERSION_PATTERN = Pattern.compile("[vV]?(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final int[] MINIMUM_VERSION = parseVersion(MINIMUM_TAG);

    private final SshCommandExecutor ssh;

    public DataEaseVersionValidator(SshCommandExecutor ssh) {
        this.ssh = ssh;
    }

    public void validate(ServerInfo source, MigrationJob job) throws Exception {
        String composePath = source.installPath() + "/" + COMPOSE_FILE_NAME;
        job.log("开始校验 DataEase 2.0 版本，读取 " + composePath + "。");
        String composeContent;
        try {
            if (MigrationService.isLocalHost(source.host())) {
                composeContent = readLocalComposeFile(composePath);
            } else {
                composeContent = ssh.readRemoteFile(source, composePath);
            }
        } catch (Exception e) {
            job.log("读取 docker-compose.yml 失败：" + safeMessage(e) + "。");
            throw new IllegalArgumentException(UNSUPPORTED_MESSAGE);
        }

        String image = findDataEaseImage(composeContent);
        if (image == null) {
            job.log("docker-compose.yml 中未找到 dataease 服务的 image 配置。");
            throw new IllegalArgumentException(UNSUPPORTED_MESSAGE);
        }
        String tag = tagOf(image);
        if (!isSupportedTag(tag)) {
            job.log("dataease 镜像标签不满足最低版本要求：" + (tag == null ? image : tag)
                    + "，最低要求为 " + MINIMUM_TAG + "。");
            throw new IllegalArgumentException(UNSUPPORTED_MESSAGE);
        }
        job.log("DataEase 2.0 版本校验通过，镜像标签：" + tag + "。");
    }

    private String readLocalComposeFile(String composePath) throws Exception {
        Path composeFile = Path.of(composePath);
        if (!Files.isRegularFile(composeFile)) {
            throw new IllegalArgumentException("未找到文件：" + composePath);
        }
        return Files.readString(composeFile, StandardCharsets.UTF_8);
    }

    /**
     * 定位 docker-compose.yml 中 {@code dataease} 服务块，并返回该块内的镜像值。
     * 采用行级解析，避免引入额外的 YAML 依赖；按缩进识别服务块边界，防止把 mysql 等
     * 相邻服务的 image 误认为 dataease 的镜像。
     */
    static String findDataEaseImage(String composeContent) {
        if (composeContent == null || composeContent.isBlank()) {
            return null;
        }
        String[] lines = composeContent.split("\\r?\\n");
        Integer dataEaseIndent = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (dataEaseIndent == null) {
                if ("dataease".equals(keyOf(line))) {
                    dataEaseIndent = indentOf(line);
                }
                continue;
            }
            int currentIndent = indentOf(line);
            if (currentIndent <= dataEaseIndent) {
                dataEaseIndent = null;
                if ("dataease".equals(keyOf(line))) {
                    dataEaseIndent = currentIndent;
                }
                continue;
            }
            if ("image".equals(keyOf(line))) {
                return valueOf(line);
            }
        }
        return null;
    }

    /**
     * 取镜像引用最后一个冒号后的 tag，兼容带仓库地址和端口号的镜像名。
     * 若镜像还带有 {@code @sha256:...} 摘要，则先去掉摘要部分。
     */
    static String tagOf(String image) {
        if (image == null || image.isBlank()) {
            return null;
        }
        String value = image.trim();
        int digestIndex = value.indexOf('@');
        if (digestIndex >= 0) {
            value = value.substring(0, digestIndex);
        }
        int tagSeparator = value.lastIndexOf(':');
        if (tagSeparator < 0 || tagSeparator == value.length() - 1) {
            return null;
        }
        String tag = value.substring(tagSeparator + 1).trim();
        return tag.isEmpty() ? null : tag;
    }

    static boolean isSupportedTag(String tag) {
        if (tag == null) {
            return false;
        }
        if (DEV_TAG.equals(tag)) {
            return true;
        }
        int[] version = parseVersion(tag);
        return version != null && compareVersion(version, MINIMUM_VERSION) >= 0;
    }

    private static int[] parseVersion(String tag) {
        if (tag == null) {
            return null;
        }
        Matcher matcher = VERSION_PATTERN.matcher(tag.trim());
        if (!matcher.matches()) {
            return null;
        }
        return new int[]{
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))
        };
    }

    private static int compareVersion(int[] left, int[] right) {
        for (int i = 0; i < left.length; i++) {
            int comparison = Integer.compare(left[i], right[i]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static int indentOf(String line) {
        int indent = 0;
        while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
            indent++;
        }
        return indent;
    }

    private static String keyOf(String line) {
        String trimmed = line.trim();
        int colon = trimmed.indexOf(':');
        return colon < 0 ? trimmed : trimmed.substring(0, colon).trim();
    }

    private static String valueOf(String line) {
        String trimmed = line.trim();
        int colon = trimmed.indexOf(':');
        if (colon < 0) {
            return "";
        }
        String value = trimmed.substring(colon + 1).trim();
        int comment = value.indexOf(" #");
        if (comment >= 0) {
            value = value.substring(0, comment);
        }
        value = value.trim();
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            value = value.substring(1, value.length() - 1);
        }
        return value.trim();
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
