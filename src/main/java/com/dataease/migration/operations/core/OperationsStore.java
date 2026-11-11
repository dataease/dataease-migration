package com.dataease.migration.operations.core;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Embedded store for one operations process. Keep collection order across updates and restarts. */
@Component
public final class OperationsStore {
    private static final int SCHEMA_VERSION = 1;
    private static final String STORAGE_MARKER = "sqlite\n";
    private static final Pattern COLLECTION = Pattern.compile("[a-z][A-Za-z0-9_-]{0,63}");
    private static final TypeReference<LinkedHashMap<String, Map<String, Object>>> LEGACY_RECORDS =
            new TypeReference<>() {};
    private static final TypeReference<LinkedHashMap<String, Object>> RECORD = new TypeReference<>() {};
    private static final String INSERT =
            "INSERT INTO operations_records (collection, id, payload) VALUES (?, ?, ?)";
    private final Connection database;
    private final ObjectMapper mapper;

    public OperationsStore(OperationsProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        this.database = openDatabase(properties.dataDirectory());
    }

    public synchronized List<Map<String, Object>> list(String collection) {
        requireCollection(collection);
        try (PreparedStatement query = database.prepareStatement(
                "SELECT payload FROM operations_records WHERE collection = ? ORDER BY sequence")) {
            query.setString(1, collection);
            try (ResultSet rows = query.executeQuery()) {
                List<Map<String, Object>> records = new ArrayList<>();
                while (rows.next()) records.add(decode(rows.getString(1)));
                return records;
            }
        } catch (SQLException | IOException failure) {
            throw new IllegalStateException("Cannot read operations records", failure);
        }
    }

    public synchronized Map<String, Object> get(String collection, String id) {
        return find(collection, id).orElseThrow(() -> OperationsApiException.notFound("记录不存在"));
    }

    public synchronized Optional<Map<String, Object>> find(String collection, String id) {
        if (id == null) return Optional.empty();
        requireCollection(collection);
        try (PreparedStatement query = database.prepareStatement(
                "SELECT payload FROM operations_records WHERE collection = ? AND id = ?")) {
            query.setString(1, collection);
            query.setString(2, id);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? Optional.of(decode(row.getString(1))) : Optional.empty();
            }
        } catch (SQLException | IOException failure) {
            throw new IllegalStateException("Cannot read operations record", failure);
        }
    }

    public synchronized Map<String, Object> put(
            String collection, String id, Map<String, Object> value) {
        requireCollection(collection);
        if (!validId(id) || value == null)
            throw OperationsApiException.badRequest("记录标识无效");
        Map<String, Object> copy = mapper.convertValue(value, RECORD);
        copy.put("id", id);
        requireNoSecrets(copy);
        try (PreparedStatement upsert = database.prepareStatement(
                INSERT + " ON CONFLICT(collection, id) DO UPDATE SET payload = excluded.payload")) {
            write(upsert, collection, id, copy);
            return copy;
        } catch (SQLException | IOException failure) {
            throw new IllegalStateException("Cannot persist operations record", failure);
        }
    }

    public synchronized void remove(String collection, String id) {
        requireCollection(collection);
        try (PreparedStatement delete = database.prepareStatement(
                "DELETE FROM operations_records WHERE collection = ? AND id = ?")) {
            delete.setString(1, collection);
            delete.setString(2, id);
            if (delete.executeUpdate() == 0) throw OperationsApiException.notFound("记录不存在");
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot remove operations record", failure);
        }
    }

    @PreDestroy
    public synchronized void close() {
        try {
            database.close();
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot close the operations database", failure);
        }
    }

    private Connection openDatabase(Path directory) {
        Path file = directory.resolve("operations.sqlite");
        Path marker = directory.resolve(".operations-sqlite");
        Connection candidate = null;
        try {
            boolean initialized = !Files.notExists(marker, LinkOption.NOFOLLOW_LINKS);
            if (initialized) {
                requireRegularFile(marker);
                if (!STORAGE_MARKER.equals(Files.readString(marker)))
                    throw new IOException("Invalid operations storage marker");
            }
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
                // Prevent accidental restoration of stale legacy JSON after loss of the database.
                if (initialized) throw new IOException("Operations database is missing");
                Files.createFile(file);
            }
            requireRegularFile(file);
            OperationsFiles.restrictFile(file);
            for (String suffix : List.of("-wal", "-shm", "-journal")) {
                Path sidecar = file.resolveSibling(file.getFileName() + suffix);
                if (!Files.notExists(sidecar, LinkOption.NOFOLLOW_LINKS)) requireRegularFile(sidecar);
            }
            candidate = DriverManager.getConnection("jdbc:sqlite:" + file.toUri().toASCIIString());
            prepareDatabase(candidate, directory.resolve("records"), initialized);
            if (!initialized)
                OperationsFiles.writePrivate(marker, STORAGE_MARKER.getBytes(StandardCharsets.UTF_8));
            return candidate;
        } catch (SQLException | IOException | RuntimeException failure) {
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (SQLException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw new IllegalStateException("Cannot open the operations SQLite database", failure);
        }
    }

    private void prepareDatabase(Connection connection, Path legacy, boolean initialized)
            throws SQLException, IOException {
        try (Statement statement = connection.createStatement()) {
            int version;
            try (ResultSet row = statement.executeQuery("PRAGMA user_version")) {
                if (!row.next()) throw new SQLException("Missing operations database schema version");
                version = row.getInt(1);
            }
            if (version != SCHEMA_VERSION && (version != 0 || initialized))
                throw new SQLException("Unsupported operations database schema version");
            statement.execute("PRAGMA busy_timeout = 5000");
            try (ResultSet row = statement.executeQuery("PRAGMA journal_mode = WAL")) {
                if (!row.next() || !"wal".equalsIgnoreCase(row.getString(1)))
                    throw new SQLException("Operations database requires WAL support");
            }
            statement.execute("PRAGMA synchronous = FULL");
            if (version == SCHEMA_VERSION) {
                statement.execute("SELECT sequence, collection, id, payload FROM operations_records LIMIT 0");
                return;
            }

            connection.setAutoCommit(false);
            try {
                statement.executeUpdate("""
                        CREATE TABLE operations_records (
                            sequence INTEGER PRIMARY KEY,
                            collection TEXT NOT NULL,
                            id TEXT NOT NULL,
                            payload TEXT NOT NULL,
                            UNIQUE (collection, id)
                        ) STRICT
                        """);
                statement.executeUpdate(
                        "CREATE INDEX operations_records_order ON operations_records (collection, sequence)");
                importLegacy(connection, legacy);
                statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
                connection.commit();
            } catch (SQLException | IOException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
            connection.setAutoCommit(true);
        }
    }

    private void importLegacy(Connection connection, Path legacy) throws IOException, SQLException {
        if (Files.notExists(legacy, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(legacy, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid legacy operations directory");
        var reader = mapper.readerFor(LEGACY_RECORDS)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(legacy, "*.json");
                PreparedStatement insert = connection.prepareStatement(INSERT)) {
            for (Path source : files) {
                requireRegularFile(source);
                String name = source.getFileName().toString();
                String collection = name.substring(0, name.length() - ".json".length());
                requireCollection(collection);
                Map<String, Map<String, Object>> records;
                try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
                    records = reader.readValue(input);
                }
                if (records == null) throw new IOException("Missing legacy operations records");
                for (var entry : records.entrySet()) {
                    String id = entry.getKey();
                    Map<String, Object> record = entry.getValue();
                    if (!validId(id) || record == null || !id.equals(record.get("id")))
                        throw new IOException("Invalid legacy operations record identity");
                    requireNoSecrets(record);
                    write(insert, collection, id, record);
                }
            }
        }
    }

    private Map<String, Object> decode(String payload) throws IOException {
        Map<String, Object> record = mapper.readValue(payload, RECORD);
        if (record == null) throw new IOException("Missing operations record");
        return record;
    }

    private void write(PreparedStatement statement, String collection, String id, Map<String, Object> record)
            throws SQLException, IOException {
        statement.setString(1, collection);
        statement.setString(2, id);
        statement.setString(3, mapper.writeValueAsString(record));
        statement.executeUpdate();
    }

    private static void requireRegularFile(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Operations storage requires regular files");
    }

    private static void requireCollection(String collection) {
        if (collection == null || !COLLECTION.matcher(collection).matches())
            throw OperationsApiException.badRequest("集合名称无效");
    }

    private static boolean validId(String id) {
        return id != null && !id.isBlank() && id.length() <= 200;
    }

    private static void requireNoSecrets(Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach(
                    (key, item) -> {
                        String name =
                                String.valueOf(key)
                                        .replaceAll("[_-]", "")
                                        .toLowerCase(java.util.Locale.ROOT);
                        boolean secretKey =
                                name.contains("password")
                                        || name.contains("passphrase")
                                        || name.contains("privatekey")
                                        || name.equals("sshkey")
                                        || name.endsWith("token")
                                        || name.equals("secret");
                        if (!name.endsWith("ref") && !name.endsWith("reference") && secretKey) {
                            if (item != null
                                    && !(item instanceof Boolean)
                                    && !(item instanceof String text && text.isEmpty()))
                                throw OperationsApiException.badRequest("认证信息只能保存到加密凭据库");
                        }
                        requireNoSecrets(item);
                    });
        } else if (value instanceof Iterable<?> items)
            items.forEach(OperationsStore::requireNoSecrets);
    }
}
