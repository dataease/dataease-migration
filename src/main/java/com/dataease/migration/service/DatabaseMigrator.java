package com.dataease.migration.service;

import com.dataease.migration.model.DatabaseInfo;

public interface DatabaseMigrator {

    void migrate(DatabaseInfo source, DatabaseInfo target, MigrationJob job) throws Exception;
}
