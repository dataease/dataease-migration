package com.dataease.migration.model;

import jakarta.validation.constraints.NotBlank;

public record DatabaseInfo(
        @NotBlank(message = "数据库 JDBC URL 不能为空") String jdbcUrl,
        @NotBlank(message = "数据库用户不能为空") String username,
        @NotBlank(message = "数据库密码不能为空") String password
) {
}
