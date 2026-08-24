package com.dataease.migration.model;

import jakarta.validation.constraints.NotBlank;

/**
 * DataEase 服务端文件位置及连接信息。
 *
 * <p>host 和 installPath 对本地、远程迁移都必填；SSH 字段只在 host 指向远程机器时使用。
 * 因为 Bean Validation 无法根据 host 是否属于本机做条件校验，username、password、port
 * 刻意不声明全局非空/范围约束，改由 MigrationService 在任务入队前按连接方式校验。</p>
 */
public record ServerInfo(
        @NotBlank(message = "服务器 IP 不能为空") String host,
        String username,
        String password,
        int port,
        @NotBlank(message = "DataEase 安装目录不能为空") String installPath
) {
}
