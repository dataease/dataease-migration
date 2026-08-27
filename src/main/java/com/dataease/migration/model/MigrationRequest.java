package com.dataease.migration.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record MigrationRequest(
        @NotNull @Valid ServerInfo sourceServer,
        @NotNull @Valid DatabaseInfo sourceDatabase,
        @NotNull @Valid ServerInfo targetServer,
        @NotNull @Valid DatabaseInfo targetDatabase,
        boolean copySyncTaskLogs
) {
}
