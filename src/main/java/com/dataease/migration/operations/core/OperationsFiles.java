package com.dataease.migration.operations.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class OperationsFiles {
    private OperationsFiles() {}

    public static void createPrivateDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory))
                throw new IOException("State directory may not be a symbolic link");
            try {
                Files.setPosixFilePermissions(
                        directory, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
                /* Non-POSIX platforms use the process account ACL. */
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot prepare the operations data directory", exception);
        }
    }

    public static void restrictFile(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            /* See createPrivateDirectory. */
        }
    }

    /** Own the directory for this JVM. The file is never replaced or deleted while locking it. */
    public static ProcessLock acquireProcessLock(Path directory) {
        Path file = directory.resolve(".operations.lock");
        FileChannel channel = null;
        FileLock lock = null;
        try {
            if (Files.isSymbolicLink(file))
                throw new IOException("Operations lock may not be a symbolic link");
            try {
                channel =
                        FileChannel.open(
                                file,
                                Set.of(
                                        StandardOpenOption.CREATE,
                                        StandardOpenOption.WRITE,
                                        LinkOption.NOFOLLOW_LINKS),
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException exception) {
                channel =
                        FileChannel.open(
                                file,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS);
            }
            restrictFile(file);
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException alreadyOwned) {
                throw new IllegalStateException("运维数据目录已由本 JVM 的另一服务持有，请使用独立的数据目录", alreadyOwned);
            }
            if (lock == null) throw new IllegalStateException("运维数据目录已被另一个进程使用，请先停止已有服务或使用独立的数据目录");
            channel.truncate(0);
            ByteBuffer owner =
                    StandardCharsets.US_ASCII.encode(
                            Long.toString(ProcessHandle.current().pid()) + "\n");
            while (owner.hasRemaining()) channel.write(owner);
            channel.force(true);
            return new ProcessLock(channel, lock);
        } catch (IOException | RuntimeException failure) {
            if (lock != null)
                try {
                    lock.release();
                } catch (IOException suppressed) {
                    failure.addSuppressed(suppressed);
                }
            if (channel != null)
                try {
                    channel.close();
                } catch (IOException suppressed) {
                    failure.addSuppressed(suppressed);
                }
            if (failure instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("无法取得运维数据目录的独占进程锁，服务已停止启动", failure);
        }
    }

    public static final class ProcessLock implements AutoCloseable {
        // Keep retained descriptors strongly reachable: a Cleaner must not unlock a still-writing
        // JVM.
        private static final Set<ProcessLock> RETAINED_UNTIL_EXIT = ConcurrentHashMap.newKeySet();
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;
        private boolean retainedUntilExit;

        private ProcessLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        public synchronized void retainUntilProcessExit() {
            if (closed) throw new IllegalStateException("运维数据目录锁已经关闭，不能延迟释放");
            retainedUntilExit = true;
            RETAINED_UNTIL_EXIT.add(this);
        }

        @Override
        public synchronized void close() {
            if (closed || retainedUntilExit) return;
            closed = true;
            IOException failure = null;
            try {
                if (lock.isValid()) lock.release();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
            if (failure != null) throw new IllegalStateException("无法正常关闭运维数据目录锁", failure);
        }
    }

    /** Flush a private temporary file, then atomically replace the previous complete value. */
    public static void writePrivate(Path file, byte[] bytes) {
        createPrivateDirectory(file.getParent());
        Path temporary = null;
        try {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(file))
                throw new IOException("State file may not be a symbolic link");
            temporary = Files.createTempFile(file.getParent(), ".ops-", ".tmp");
            restrictFile(temporary);
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException(
                        "Operations storage requires atomic file replacement", exception);
            }
            restrictFile(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot persist operations state", exception);
        } finally {
            if (temporary != null)
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
        }
    }
}
