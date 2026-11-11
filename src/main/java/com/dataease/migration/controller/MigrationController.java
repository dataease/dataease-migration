package com.dataease.migration.controller;

import com.dataease.migration.service.MigrationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
public class MigrationController {
    private final MigrationService migrationService;

    public MigrationController(MigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @PostMapping("/api/migrations")
    public ResponseEntity<Map<String, String>> start() {
        throw new ResponseStatusException(HttpStatus.GONE, "请从运维中心创建迁移任务，完成预检查、保护备份和目标覆盖确认。");
    }

    @GetMapping(value = "/api/migrations/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            migrationService.getJob(id).subscribe(emitter);
            return emitter;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

}
