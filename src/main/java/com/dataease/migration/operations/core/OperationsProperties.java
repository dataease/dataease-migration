package com.dataease.migration.operations.core;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** Paths shared by the operations services. No state is kept under the web root. */
@Component
public final class OperationsProperties implements AutoCloseable {
    private final Path dataDirectory;
    private final OperationsFiles.ProcessLock processLock;

    public OperationsProperties(
            @Value("${operations.data-directory:${OPERATIONS_DATA_DIRECTORY:data/operations}}")
                    String directory) {
        dataDirectory = Path.of(directory).toAbsolutePath().normalize();
        OperationsFiles.createPrivateDirectory(dataDirectory);
        processLock = OperationsFiles.acquireProcessLock(dataDirectory);
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    /** Called when shutdown cannot stop active tasks; kernel ownership must outlive the context. */
    public void retainLockUntilProcessExit() {
        processLock.retainUntilProcessExit();
    }

    @Override
    @PreDestroy
    public void close() {
        processLock.close();
    }
}
