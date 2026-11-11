package com.dataease.migration.operations.api;

import com.dataease.migration.operations.app.OperationsService;
import com.dataease.migration.operations.auth.OperationsAuthService;
import com.dataease.migration.operations.core.OperationsApiException;
import com.dataease.migration.operations.media.ArchiveSupport;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/api/ops")
public final class OperationsController {
    private final OperationsService operations;
    private final OperationsAuthService authentication;
    private final ConcurrentHashMap<String, Download> exports = new ConcurrentHashMap<>();
    private final Semaphore exportSlots = new Semaphore(4);

    private record Download(Path file, String owner, Instant expires) {}

    public OperationsController(
            OperationsService operations, OperationsAuthService authentication) {
        this.operations = operations;
        this.authentication = authentication;
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        return operations.state();
    }

    @PostMapping("/instances/probe")
    public Map<String, Object> probe(@RequestBody Map<String, Object> input) throws Exception {
        return operations.probe(input);
    }

    @PostMapping("/instances")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> input) {
        return ResponseEntity.status(201).body(operations.register(input));
    }

    @PutMapping("/instances/{id}/connection")
    public Map<String, Object> connection(
            @PathVariable String id, @RequestBody Map<String, Object> input) throws Exception {
        return operations.updateConnection(id, input);
    }

    @PutMapping("/instances/{id}/environment")
    public Map<String, Object> environment(
            @PathVariable String id, @RequestBody Map<String, Object> input) {
        return operations.updateEnvironment(id, input);
    }

    @PostMapping("/instances/{id}/service")
    public ResponseEntity<Map<String, Object>> service(
            @PathVariable String id, @RequestBody Map<String, Object> input) throws Exception {
        return ResponseEntity.accepted().body(operations.service(id, input));
    }

    @GetMapping("/instances/{id}/automation")
    public Map<String, Object> automation(@PathVariable String id) throws Exception {
        return operations.automation(id);
    }

    @PostMapping("/prechecks")
    public Map<String, Object> precheck(@RequestBody Map<String, Object> input) throws Exception {
        return operations.precheck(input);
    }

    @PostMapping("/tasks")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, Object> input)
            throws Exception {
        return ResponseEntity.accepted().body(operations.submit(input));
    }

    @GetMapping("/tasks/{id}")
    public Map<String, Object> task(@PathVariable String id) {
        return operations.task(id);
    }

    @PostMapping("/tasks/{id}/abort")
    public Map<String, Object> abort(
            @PathVariable String id, @RequestBody Map<String, Object> input) throws Exception {
        return operations.abortAcceptance(id, input);
    }

    @PostMapping("/tasks/{id}/reconcile")
    public Map<String, Object> reconcile(
            @PathVariable String id, @RequestBody Map<String, Object> input) throws Exception {
        return operations.reconcile(id, input);
    }

    @GetMapping("/tasks/{id}/logs")
    public List<String> logs(@PathVariable String id) {
        return operations.logs(id);
    }

    @PostMapping("/tasks/{id}/acceptance/check")
    public Map<String, Object> acceptanceCheck(@PathVariable String id) throws Exception {
        return operations.acceptanceCheck(id);
    }

    @PutMapping("/tasks/{id}/acceptance")
    public Map<String, Object> acceptance(
            @PathVariable String id, @RequestBody Map<String, Object> input) {
        return operations.saveAcceptance(id, input);
    }

    @PostMapping("/tasks/{id}/acceptance/complete")
    public Map<String, Object> completeAcceptance(@PathVariable String id) throws Exception {
        return operations.finishAcceptance(id);
    }

    @PostMapping(value = "/packages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "kind", defaultValue = "package") String kind)
            throws Exception {
        try (var input = file.getInputStream()) {
            return ResponseEntity.status(201)
                    .body(operations.addPackage(input, kind, file.getOriginalFilename()));
        }
    }

    @PostMapping("/packages/{id}/verify")
    public Map<String, Object> verify(@PathVariable String id) throws Exception {
        return operations.verifyPackage(id);
    }

    @PutMapping("/packages/{id}/manifest")
    public Map<String, Object> manifest(
            @PathVariable String id, @RequestBody Map<String, Object> manifest) throws Exception {
        return operations.attachManifest(id, manifest);
    }

    @DeleteMapping("/packages/{id}")
    public ResponseEntity<Void> removePackage(@PathVariable String id) throws Exception {
        operations.removePackage(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/packages/{id}/download")
    public ResponseEntity<Resource> downloadPackage(@PathVariable String id) throws Exception {
        return download(operations.packagePath(id));
    }

    @PostMapping(value = "/backups/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> importBackup(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "password", defaultValue = "") String suppliedPassword)
            throws Exception {
        char[] password = suppliedPassword.toCharArray();
        try (var input = file.getInputStream()) {
            return ResponseEntity.status(201)
                    .body(
                            operations.addPackage(
                                    input, "backup", file.getOriginalFilename(), password));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @PostMapping("/backups/{id}/export")
    public Map<String, Object> exportBackup(
            @PathVariable String id,
            @RequestBody Map<String, String> input,
            HttpServletRequest request)
            throws Exception {
        cleanupExports();
        if (!exportSlots.tryAcquire())
            throw new OperationsApiException(HttpStatus.TOO_MANY_REQUESTS, "已有多个备份正在导出，请完成下载后重试。");
        String supplied = input.getOrDefault("password", "");
        char[] password = supplied == null ? new char[0] : supplied.toCharArray();
        Path file = null;
        try {
            String owner = exportOwner(request);
            file = operations.exportBackup(id, password);
            String ticket = UUID.randomUUID().toString();
            Instant expires = Instant.now().plusSeconds(600);
            long size = Files.size(file);
            String digest = ArchiveSupport.sha256(file);
            exports.put(ticket, new Download(file, owner, expires));
            return Map.of(
                    "downloadUrl",
                    "/api/ops/backups/exports/" + ticket,
                    "expiresAt",
                    expires.toString(),
                    "sizeBytes",
                    size,
                    "sha256",
                    digest);
        } catch (Exception failure) {
            try {
                if (file != null) ArchiveSupport.deleteTree(file.getParent());
            } finally {
                exportSlots.release();
            }
            throw failure;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @GetMapping("/backups/exports/{ticket}")
    public ResponseEntity<Resource> downloadExport(
            @PathVariable String ticket, HttpServletRequest request) throws Exception {
        Download download = exports.get(ticket);
        if (download == null || download.expires().isBefore(Instant.now())) {
            cleanupExports();
            throw new OperationsApiException(HttpStatus.GONE, "下载链接已失效，请重新导出备份。");
        }
        if (!download.owner().equals(exportOwner(request)))
            throw new OperationsApiException(HttpStatus.FORBIDDEN, "请使用发起导出的管理员会话下载。");
        if (!exports.remove(ticket, download))
            throw new OperationsApiException(HttpStatus.GONE, "该导出包已开始下载，请重新导出后再次下载。");
        Path file = download.file();
        try {
            long size = Files.size(file);
            InputStream stream =
                    new FilterInputStream(Files.newInputStream(file)) {
                        private boolean closed;

                        @Override
                        public synchronized void close() throws IOException {
                            if (closed) return;
                            closed = true;
                            try {
                                super.close();
                            } finally {
                                try {
                                    ArchiveSupport.deleteTree(file.getParent());
                                } finally {
                                    exportSlots.release();
                                }
                            }
                        }
                    };
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment()
                                    .filename("dataease-backup.debackup", StandardCharsets.UTF_8)
                                    .build()
                                    .toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .contentLength(size)
                    .body(new InputStreamResource(stream));
        } catch (Exception failure) {
            try {
                ArchiveSupport.deleteTree(file.getParent());
            } finally {
                exportSlots.release();
            }
            throw failure;
        }
    }

    private String exportOwner(HttpServletRequest request) {
        return authentication
                .session(request)
                .orElseThrow(() -> new OperationsApiException(HttpStatus.UNAUTHORIZED, "请先登录管理员账号"))
                .csrfToken();
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanupExports() throws IOException {
        for (var entry : exports.entrySet())
            if (entry.getValue().expires().isBefore(Instant.now())
                    && exports.remove(entry.getKey(), entry.getValue())) {
                try {
                    ArchiveSupport.deleteTree(entry.getValue().file().getParent());
                } finally {
                    exportSlots.release();
                }
            }
    }

    @PreDestroy
    public void closeExports() throws IOException {
        for (var entry : exports.entrySet())
            if (exports.remove(entry.getKey(), entry.getValue()))
                try {
                    ArchiveSupport.deleteTree(entry.getValue().file().getParent());
                } finally {
                    exportSlots.release();
                }
    }

    @PostMapping("/backups/{id}/verify")
    public Map<String, Object> verifyBackup(@PathVariable String id) throws Exception {
        return operations.verifyBackup(id);
    }

    @GetMapping("/backups/{id}/download")
    public ResponseEntity<Resource> downloadBackup(@PathVariable String id) throws Exception {
        return download(operations.backupPath(id));
    }

    @PostMapping("/plans")
    public ResponseEntity<Map<String, Object>> createPlan(@RequestBody Map<String, Object> input) {
        return ResponseEntity.status(201).body(operations.savePlan(null, input));
    }

    @PutMapping("/plans/{id}")
    public Map<String, Object> updatePlan(
            @PathVariable String id, @RequestBody Map<String, Object> input) {
        return operations.savePlan(id, input);
    }

    @DeleteMapping("/plans/{id}")
    public ResponseEntity<Void> removePlan(@PathVariable String id) {
        operations.removePlan(id);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Resource> download(Path file) throws Exception {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.getFileName().toString(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentLength(Files.size(file))
                .body(new FileSystemResource(file));
    }
}
