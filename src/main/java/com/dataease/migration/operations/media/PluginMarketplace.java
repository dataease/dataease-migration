package com.dataease.migration.operations.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.*;

/** A read-only adapter to the product's official catalogue; no catalogue-supplied callbacks execute. */
@Service
public final class PluginMarketplace {
    private static final String API = "https://apps.fit2cloud.com/app-store/v1/software/dataease";
    private final DownloadHttp http;
    private final ObjectMapper json;
    public PluginMarketplace(DownloadHttp http, ObjectMapper json) { this.http=http; this.json=json; }
    private JsonNode load() throws Exception {
        JsonNode value = json.readTree(http.json(API));
        if (!value.path("id").asText().equals("dataease") || !value.path("apps").isArray())
            throw new IllegalArgumentException("应用市场返回格式已变化，请使用下载地址或上传文件");
        return value;
    }
    public List<Map<String,Object>> catalogue() throws Exception {
        List<Map<String,Object>> result = new ArrayList<>();
        for (JsonNode app : load().path("apps")) {
            if (!app.path("valid").asBoolean(false)) continue;
            List<Map<String,Object>> versions = new ArrayList<>();
            for (JsonNode v : app.path("versions")) {
                if (!v.path("valid").asBoolean(false) || !v.path("downloadUrl").asText().toLowerCase(Locale.ROOT).matches(".*\\.jar(?:\\?.*)?$")) continue;
                versions.add(Map.of("id",v.path("id").asText(),"name",v.path("name").asText(),
                        "notes",plain(v.path("readMe").asText()),"publishedAt",v.path("lastModified").asLong()));
            }
            if (versions.isEmpty()) continue;
            result.add(Map.of("id",app.path("id").asText(),"name",app.path("name").asText(),
                    "description",app.path("title").asText(),"enterprise",app.path("additionalProperties").path("enterprise").asBoolean(false),
                    "notes",plain(app.path("readMe").asText()),"versions",versions));
        }
        return result;
    }
    public Map<String,String> resolve(String appId, String versionId) throws Exception {
        if (!appId.matches("[A-Za-z0-9_-]{1,100}") || !versionId.matches("[A-Za-z0-9_.-]{1,100}")) throw new IllegalArgumentException("请选择应用市场中的明确插件版本");
        for (JsonNode app : load().path("apps")) if (app.path("id").asText().equals(appId) && app.path("valid").asBoolean(false))
            for (JsonNode version : app.path("versions")) if (version.path("id").asText().equals(versionId) && version.path("valid").asBoolean(false)) {
                String url=version.path("downloadUrl").asText();
                if (!url.matches("https://apps-assets\\.fit2cloud\\.com/.*\\.jar(?:\\?.*)?$")) throw new IllegalArgumentException("应用市场未提供受支持的插件直接下载地址");
                return Map.of("url",url,"name",app.path("name").asText()+" · "+version.path("name").asText());
            }
        throw new IllegalArgumentException("此插件版本已不在应用市场中，请刷新后重试");
    }
    private static String plain(String text) {
        text=text.replaceAll("[\\r\\n]+"," ").replaceAll("[#*`<>]", "").strip();
        return text.substring(0,Math.min(text.length(),300));
    }
}
