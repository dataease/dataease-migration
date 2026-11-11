package com.dataease.migration.operations.media;

import java.io.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Bounded ZIP/TAR.GZ support. Links and special files are never materialised. */
public final class ArchiveSupport {
    private static final long MAX_BYTES = 50L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 200_000;

    private ArchiveSupport() {}

    public static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public record TarIndex(Map<String, byte[]> metadata, Set<String> files) {}

    /** Reads Docker-save metadata without unpacking image layers or executing any image. */
    public static TarIndex imageIndex(Path archive) throws Exception {
        try (BufferedInputStream source = new BufferedInputStream(Files.newInputStream(archive))) {
            source.mark(2);
            boolean gzip = source.read() == 0x1f && source.read() == 0x8b;
            source.reset();
            try (InputStream input = gzip ? new GZIPInputStream(source) : source) {
                Map<String, byte[]> metadata = new HashMap<>();
                Set<String> files = new HashSet<>();
                Map<String, String> global = new HashMap<>(), next = Map.of();
                String longName = null;
                long total = 0, retained = 0;
                int entries = 0;
                byte[] header = new byte[512];
                while (true) {
                    if (input.readNBytes(header, 0, 512) != 512) throw new EOFException("镜像归档不完整");
                    if (zero(header)) {
                        byte[] end = input.readNBytes(512);
                        if (end.length != 512 || !zero(end)) throw new EOFException("镜像结束标记不完整");
                        int trailing = 0, value;
                        while ((value = input.read()) != -1)
                            if (value != 0 || ++trailing > 1024 * 1024)
                                throw new IOException("镜像尾部异常");
                        return new TarIndex(Map.copyOf(metadata), Set.copyOf(files));
                    }
                    verifyTarChecksum(header);
                    if (++entries > MAX_ENTRIES) throw new IOException("镜像条目数量过多");
                    long length = number(header, 124, 12);
                    if (length < 0 || length > MAX_BYTES) throw new IOException("镜像条目过大");
                    char type = (char) header[156];
                    String name = text(header, 0, 100), prefix = text(header, 345, 155);
                    if (!prefix.isEmpty()) name = prefix + "/" + name;
                    if (type == 'x' || type == 'g' || type == 'L') {
                        if (length > 1024 * 1024) throw new IOException("镜像扩展头过大");
                        byte[] bytes = input.readNBytes((int) length);
                        if (bytes.length != length) throw new EOFException("镜像扩展头不完整");
                        if (type == 'L') longName = utf8(bytes).replace("\0", "").stripTrailing();
                        else if (type == 'g') global.putAll(pax(bytes));
                        else next = pax(bytes);
                        skip(input, padding(length));
                        total += 512 + length + padding(length);
                        if (total > MAX_BYTES) throw new IOException("镜像体积超过上限");
                        continue;
                    }
                    Map<String, String> extended = new HashMap<>(global);
                    extended.putAll(next);
                    name = extended.getOrDefault("path", longName == null ? name : longName);
                    if (extended.containsKey("size")) length = Long.parseLong(extended.get("size"));
                    if (length < 0 || length > MAX_BYTES) throw new IOException("镜像条目过大");
                    next = Map.of();
                    longName = null;
                    while (name.startsWith("./")) name = name.substring(2);
                    if (type == '5') {
                        if (length != 0) throw new IOException("镜像目录条目无效");
                    } else if (type == 0 || type == '0') {
                        if (name.isBlank()
                                || name.startsWith("/")
                                || name.contains("\\")
                                || Arrays.asList(name.split("/")).contains("..")
                                || !files.add(name)) throw new IOException("镜像含非法或重复路径");
                        boolean small =
                                length <= 1024 * 1024
                                        && (name.endsWith(".json")
                                                || name.matches("blobs/sha256/[a-f0-9]{64}"));
                        if (small) {
                            retained += length;
                            if (retained > 16 * 1024 * 1024) throw new IOException("镜像元数据过大");
                            byte[] bytes = input.readNBytes((int) length);
                            if (bytes.length != length) throw new EOFException("镜像元数据不完整");
                            metadata.put(name, bytes);
                        } else skip(input, length);
                    } else throw new IOException("镜像归档含不支持的链接或特殊条目");
                    skip(input, padding(length));
                    total += 512 + length + padding(length);
                    if (total > MAX_BYTES) throw new IOException("镜像体积超过上限");
                }
            }
        }
    }

    public static void extract(Path archive, Path directory) throws Exception {
        Path root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("解包目录不能是符号链接");
        try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(archive))) {
            input.mark(4);
            int first = input.read(), second = input.read();
            input.reset();
            if (first == 'P' && second == 'K') unzip(input, root, zipEntries(archive));
            else if (first == 0x1f && second == 0x8b) {
                try (GZIPInputStream gzip = new GZIPInputStream(input)) {
                    untar(gzip, root);
                }
            } else throw new IOException("仅支持完整的 ZIP / JAR 或 TAR.GZ 归档");
        }
    }

    /** Checks central-directory structure in addition to ZipInputStream's per-entry CRC checks. */
    private static Set<String> zipEntries(Path archive) throws IOException {
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (names.size() >= MAX_ENTRIES || !names.add(entry.getName()))
                    throw new IOException("归档文件过多或含重复条目");
                if (entry.getSize() > MAX_BYTES) throw new IOException("归档条目过大");
            }
        }
        if (names.isEmpty()) throw new IOException("归档中没有可读取的条目");
        return names;
    }

    private static Path destination(Path root, String name) throws IOException {
        if (name == null
                || name.isBlank()
                || name.chars().anyMatch(value -> value < 32)
                || name.contains("\\")
                || name.startsWith("/")
                || name.matches("^[A-Za-z]:.*")) throw new IOException("归档含非法路径");
        Path relative;
        try {
            relative = Path.of(name);
        } catch (InvalidPathException exception) {
            throw new IOException("归档路径无效");
        }
        for (Path part : relative)
            if (part.toString().equals("..")) throw new IOException("归档目录穿越已阻止");
        Path result = root.resolve(relative).normalize();
        if (!result.startsWith(root) || result.equals(root)) throw new IOException("归档目录穿越已阻止");
        for (Path ancestor = result.getParent();
                ancestor != null && ancestor.startsWith(root);
                ancestor = ancestor.getParent()) {
            if (Files.isSymbolicLink(ancestor)) throw new IOException("解包路径含符号链接");
            if (ancestor.equals(root)) break;
        }
        if (Files.isSymbolicLink(result)) throw new IOException("解包路径含符号链接");
        return result;
    }

    private static void unzip(InputStream source, Path root, Set<String> declared)
            throws Exception {
        long total = 0;
        Set<Path> seen = new HashSet<>();
        Set<String> actual = new HashSet<>();
        try (ZipInputStream input = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (actual.size() >= MAX_ENTRIES
                        || !actual.add(entry.getName())
                        || !declared.contains(entry.getName()))
                    throw new IOException("ZIP 索引与文件条目不一致");
                if (entry.getName().equals("./")) {
                    if (input.read() != -1) throw new IOException("ZIP 目录不能包含文件内容");
                    input.closeEntry();
                    continue;
                }
                Path target = destination(root, entry.getName());
                if (!seen.add(target)) throw new IOException("归档存在重复路径");
                if (entry.isDirectory()) {
                    if (input.read() != -1) throw new IOException("ZIP 目录不能包含文件内容");
                    Files.createDirectories(target);
                    input.closeEntry();
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream output =
                        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_BYTES) throw new IOException("归档解包体积超过 50GB 上限");
                        output.write(buffer, 0, count);
                    }
                }
                input.closeEntry();
            }
        }
        if (!actual.equals(declared)) throw new IOException("ZIP 文件不完整");
    }

    private static void untar(InputStream input, Path root) throws Exception {
        byte[] header = new byte[512];
        long total = 0;
        int count = 0;
        String longName = null;
        Map<String, String> global = new HashMap<>(), next = Map.of();
        Set<Path> seen = new HashSet<>();
        while (true) {
            if (input.readNBytes(header, 0, 512) != 512) throw new EOFException("TAR 归档缺少完整的结束标记");
            if (zero(header)) {
                byte[] ending = input.readNBytes(512);
                if (ending.length != 512 || !zero(ending)) throw new EOFException("TAR 归档结束标记不完整");
                // Reading to EOF verifies the gzip trailer and rejects hidden appended payloads.
                int trailing = 0, value;
                while ((value = input.read()) != -1)
                    if (value != 0 || ++trailing > 1024 * 1024) throw new IOException("TAR 归档尾部异常");
                return;
            }
            verifyTarChecksum(header);
            total += 512;
            if (total > MAX_BYTES) throw new IOException("归档解包体积超过 50GB 上限");
            if (++count > MAX_ENTRIES) throw new IOException("归档文件数量超过上限");
            long length = number(header, 124, 12);
            if (length < 0 || length > MAX_BYTES) throw new IOException("归档条目过大");
            char type = (char) header[156];
            String name = text(header, 0, 100), prefix = text(header, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            if (type == 'x' || type == 'g' || type == 'L') {
                if (length > 1024 * 1024) throw new IOException("归档扩展头过大");
                total += length + padding(length);
                if (total > MAX_BYTES) throw new IOException("归档解包体积超过 50GB 上限");
                byte[] metadata = input.readNBytes((int) length);
                if (metadata.length != length) throw new EOFException("归档扩展头不完整");
                skip(input, padding(length));
                if (type == 'L') longName = utf8(metadata).replace("\0", "").stripTrailing();
                else if (type == 'g') global.putAll(pax(metadata));
                else next = pax(metadata);
                continue;
            }
            Map<String, String> extended = new HashMap<>(global);
            extended.putAll(next);
            name = extended.getOrDefault("path", longName == null ? name : longName);
            if (extended.containsKey("size")) {
                try {
                    length = Long.parseLong(extended.get("size"));
                } catch (NumberFormatException exception) {
                    throw new IOException("PAX 条目大小无效");
                }
                if (length < 0 || length > MAX_BYTES) throw new IOException("PAX 条目大小超过上限");
            }
            next = Map.of();
            longName = null;
            if ((name.equals(".") || name.equals("./")) && type == '5') {
                skip(input, length + padding(length));
                continue;
            }
            Path target = destination(root, name);
            if (!seen.add(target)) throw new IOException("归档存在重复路径");
            if (type == '5') {
                if (length != 0) throw new IOException("TAR 目录条目大小无效");
                Files.createDirectories(target);
            } else if (type == '0' || type == 0) {
                total += length + padding(length);
                if (total > MAX_BYTES) throw new IOException("归档解包体积超过 50GB 上限");
                Files.createDirectories(target.getParent());
                try (OutputStream output =
                        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    copy(input, output, length);
                }
                if ((number(header, 100, 8) & 0111) != 0
                        && !target.toFile().setExecutable(true, true))
                    throw new IOException("无法设置离线入口执行权限");
            } else throw new IOException("归档含链接、设备或不支持的条目，已拒绝解包");
            skip(input, padding(length));
        }
    }

    private static void verifyTarChecksum(byte[] header) throws IOException {
        long expected = number(header, 148, 8), actual = 0;
        for (int index = 0; index < header.length; index++)
            actual += index >= 148 && index < 156 ? 32 : Byte.toUnsignedInt(header[index]);
        if (actual != expected) throw new IOException("TAR 文件头校验失败");
    }

    private static Map<String, String> pax(byte[] metadata) throws IOException {
        Map<String, String> result = new HashMap<>();
        int cursor = 0;
        while (cursor < metadata.length) {
            int space = cursor;
            while (space < metadata.length && metadata[space] != ' ') space++;
            if (space == metadata.length) throw new IOException("PAX 扩展头无效");
            int length;
            try {
                length =
                        Integer.parseInt(
                                new String(
                                        metadata,
                                        cursor,
                                        space - cursor,
                                        StandardCharsets.US_ASCII));
            } catch (NumberFormatException exception) {
                throw new IOException("PAX 长度无效");
            }
            if (length <= space - cursor + 2
                    || length > metadata.length - cursor
                    || metadata[cursor + length - 1] != '\n') throw new IOException("PAX 记录不完整");
            String field = utf8(Arrays.copyOfRange(metadata, space + 1, cursor + length - 1));
            int equal = field.indexOf('=');
            if (equal <= 0) throw new IOException("PAX 记录无效");
            result.put(field.substring(0, equal), field.substring(equal + 1));
            cursor += length;
        }
        return result;
    }

    private static long number(byte[] bytes, int start, int length) throws IOException {
        if ((bytes[start] & 0x80) != 0) {
            byte[] encoded = Arrays.copyOfRange(bytes, start, start + length);
            encoded[0] &= 0x7f;
            BigInteger number = new BigInteger(1, encoded);
            if (number.bitLength() > 63) throw new IOException("TAR 数值超出范围");
            return number.longValue();
        }
        String value = text(bytes, start, length).trim();
        if (value.isEmpty()) return 0;
        try {
            return Long.parseLong(value, 8);
        } catch (NumberFormatException exception) {
            throw new IOException("无效 TAR 数值头");
        }
    }

    private static String text(byte[] bytes, int start, int length) throws IOException {
        int end = start;
        while (end < start + length && bytes[end] != 0) end++;
        return utf8(Arrays.copyOfRange(bytes, start, end));
    }

    private static String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static boolean zero(byte[] bytes) {
        for (byte value : bytes) if (value != 0) return false;
        return true;
    }

    private static long padding(long length) {
        return (512 - length % 512) % 512;
    }

    private static void copy(InputStream input, OutputStream output, long length)
            throws IOException {
        byte[] buffer = new byte[65536];
        while (length > 0) {
            int count = input.read(buffer, 0, (int) Math.min(length, buffer.length));
            if (count < 0) throw new EOFException("归档不完整");
            output.write(buffer, 0, count);
            length -= count;
        }
    }

    private static void skip(InputStream input, long length) throws IOException {
        while (length > 0) {
            long count = input.skip(length);
            if (count == 0) {
                if (input.read() < 0) throw new EOFException("归档不完整");
                count = 1;
            }
            length -= count;
        }
    }

    public static void zip(Path directory, Path destination) throws Exception {
        Path root = directory.toAbsolutePath().normalize(),
                outputPath = destination.toAbsolutePath().normalize();
        if (outputPath.startsWith(root)) throw new IOException("备份输出不能位于待归档目录内");
        try (ZipOutputStream output =
                        new ZipOutputStream(
                                Files.newOutputStream(outputPath, StandardOpenOption.CREATE_NEW));
                var files = Files.walk(root)) {
            for (Path file : files.toList()) {
                if (Files.isSymbolicLink(file)) throw new IOException("不归档符号链接");
                if (!Files.isRegularFile(file)) continue;
                output.putNextEntry(
                        new ZipEntry(root.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
    }

    public static void deleteTree(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }
}
