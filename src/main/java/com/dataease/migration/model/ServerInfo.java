package com.dataease.migration.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ServerInfo(
        @NotBlank(message = "服务器 IP 不能为空") String host,
        @NotBlank(message = "服务器用户名不能为空") String username,
        @NotBlank(message = "服务器密码不能为空") String password,
        @Min(value = 1, message = "SSH 端口必须介于 1 到 65535")
        @Max(value = 65535, message = "SSH 端口必须介于 1 到 65535") int port,
        @NotBlank(message = "DataEase 安装目录不能为空") String installPath
) {
}
