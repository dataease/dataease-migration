package com.dataease.migration.operations.api;
import com.dataease.migration.operations.media.*;
import com.dataease.migration.operations.core.OperationsApiException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;
@RestController
@RequestMapping("/api/ops")
public final class PackageDownloadController {
    private final PackageDownloadService downloads;private final PluginMarketplace market;
    public PackageDownloadController(PackageDownloadService downloads,PluginMarketplace market){this.downloads=downloads;this.market=market;}
    @GetMapping("/marketplace/plugins") public List<Map<String,Object>> catalogue() {
        try { return market.catalogue(); }
        catch(Exception unavailable) { throw OperationsApiException.conflict("无法读取应用市场，请稍后重试，也可使用下载地址或上传文件"); }
    }
    @PostMapping("/downloads") public ResponseEntity<Map<String,Object>> create(@RequestBody Map<String,Object> input) throws Exception {
        try { return ResponseEntity.accepted().body(downloads.create(input)); }
        catch(IllegalArgumentException invalid) { throw OperationsApiException.badRequest("下载参数或所选应用市场版本无效，请核对后重试"); }
    }
    @PostMapping("/downloads/{id}/actions") public Map<String,Object> action(@PathVariable String id,@RequestBody Map<String,Object> input) throws Exception {return downloads.action(id,input);}
    @DeleteMapping("/downloads/{id}") public ResponseEntity<Void> remove(@PathVariable String id) throws Exception {downloads.remove(id);return ResponseEntity.noContent().build();}
}
