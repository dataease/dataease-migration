package com.dataease.migration.service;

import com.dataease.migration.model.DatabaseConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseConnectionTest {

    @Test
    void parsesMysqlUrlWithPortAndParameters() {
        DatabaseConnection connection =
                DatabaseConnection.fromJdbcUrl("jdbc:mysql://db.example:3307/dataease?useUnicode=true");

        assertEquals("db.example", connection.host());
        assertEquals(3307, connection.port());
        assertEquals("dataease", connection.database());
    }

    @Test
    void defaultsMysqlPort() {
        assertEquals(3306, DatabaseConnection.fromJdbcUrl("jdbc:mysql://localhost/dataease").port());
    }

    @Test
    void rejectsNonMysqlUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseConnection.fromJdbcUrl("jdbc:postgresql://localhost/dataease"));
    }
}
