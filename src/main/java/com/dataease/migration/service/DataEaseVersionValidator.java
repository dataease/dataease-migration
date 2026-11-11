package com.dataease.migration.service;

import com.dataease.migration.model.ServerInfo;
import org.springframework.stereotype.Component;
import com.dataease.migration.operations.exec.ComposeManager;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.util.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Uses the same resolved Compose configuration as instance registration. */
@Component
public class DataEaseVersionValidator {
    private static final String MINIMUM_TAG = "v2.10.26";
    private static final String DEV_TAG = "dev-v2";
    private static final String UNSUPPORTED_MESSAGE = "dataease最低版本为v2.10.26";
    private static final Pattern VERSION_PATTERN = Pattern.compile("[vV]?(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final int[] MINIMUM_VERSION = parseVersion(MINIMUM_TAG);

    private final ComposeManager compose;

    public DataEaseVersionValidator(ComposeManager compose) {
        this.compose = compose;
    }

    public void validate(ServerInfo source, MigrationJob job) throws Exception {
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("host", source.host());
        instance.put("path", source.installPath());
        instance.put("connection", MigrationService.isLocalServer(source) ? "local" : "ssh");
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("sshPort", String.valueOf(source.port()));
        if (source.username() != null) credentials.put("sshUser", source.username());
        if (source.password() != null) credentials.put("sshPassword", source.password());
        if (source.privateKey() != null) credentials.put("privateKey", source.privateKey());
        if (source.passphrase() != null) credentials.put("passphrase", source.passphrase());
        if (source.hostKey() != null) credentials.put("hostKey", source.hostKey());
        job.log("开始复核源端实际Compose镜像版本。");
        String image = findImage(compose.config(instance, credentials));
        if (image == null) throw new IllegalArgumentException(UNSUPPORTED_MESSAGE);
        String tag = tagOf(image);
        if (!isSupportedTag(tag) || !(DEV_TAG.equals(tag) || tag != null && tag.matches("[vV]?2\\.\\d+\\.\\d+"))) {
            job.log("dataease 镜像标签不满足最低版本要求：" + (tag == null ? image : tag)
                    + "，最低要求为 " + MINIMUM_TAG + "。");
            throw new IllegalArgumentException(UNSUPPORTED_MESSAGE);
        }
        job.log("DataEase 2.0 版本校验通过，镜像标签：" + tag + "。");
    }

    static String findDataEaseImage(String content) {
        if (content == null || content.isBlank()) return null;
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
        return parsed instanceof Map<?, ?> map ? findImage(map) : null;
    }

    private static String findImage(Map<?, ?> config) {
        Map<?, ?> services = config.get("services") instanceof Map<?, ?> map ? map : config;
        String found = null;
        for (var entry : services.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> service)
                    || !(service.get("image") instanceof String image)) continue;
            String repository = image.substring(image.lastIndexOf('/') + 1).split(":")[0];
            if (!"dataease".equals(entry.getKey()) && !repository.equals("dataease")
                    && !repository.startsWith("dataease-")) continue;
            if (found != null) throw new IllegalArgumentException("源端Compose包含多个DataEase应用");
            found = image;
        }
        return found;
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

}
