package com.dataease.migration.operations.media;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Validates every redirect and resolves the actual socket through a filtering DNS resolver. */
@Component
public final class DownloadHttp implements AutoCloseable {
    private final CloseableHttpClient client;
    private final Set<String> privateHosts;
    private final Map<String, Integer> privatePorts = new HashMap<>();

    public DownloadHttp(@Value("${operations.download.private-origins:}") String privateOrigins) {
        Set<String> names = new HashSet<>();
        for (String value : privateOrigins.split(",")) {
            if (value.isBlank()) continue;
            URI uri = URI.create(value.trim());
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))
                throw new IllegalArgumentException("下载内网白名单必须为明确的HTTP(S)来源");
            names.add(uri.getHost().toLowerCase(Locale.ROOT));
            privatePorts.put(origin(uri), port(uri));
        }
        privateHosts = Set.copyOf(names);
        DnsResolver dns = new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                if (!privateHosts.contains(host.toLowerCase(Locale.ROOT)))
                    for (InetAddress address : addresses) if (!publicAddress(address)) throw new UnknownHostException("下载地址不允许访问内网或保留地址");
                return addresses;
            }
            public String resolveCanonicalHostname(String host) throws UnknownHostException { resolve(host); return host; }
        };
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(dns)
                .setMaxConnTotal(24).setMaxConnPerRoute(16)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(15))
                        .setSocketTimeout(Timeout.ofSeconds(30)).build()).build();
        client = HttpClients.custom().setConnectionManager(manager).disableRedirectHandling()
                .disableAutomaticRetries().disableCookieManagement().disableContentCompression()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(20))
                        .setResponseTimeout(Timeout.ofSeconds(30)).build()).build();
    }

    public URI validate(String value) {
        try {
            URI uri = new URI(value.trim()).normalize();
            if (uri.toASCIIString().length() > 16384 || !Set.of("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || port(uri) < 1 || port(uri) > 65535 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            if (privateHosts.contains(uri.getHost().toLowerCase(Locale.ROOT)) && !privatePorts.containsKey(origin(uri)))
                throw new IllegalArgumentException("此内网来源端口不在下载白名单内");
            for (InetAddress address : InetAddress.getAllByName(uri.getHost()))
                if (!publicAddress(address) && !privatePorts.containsKey(origin(uri)))
                    throw new IllegalArgumentException("下载地址不允许访问内网或保留地址；内网仓库需由管理员配置来源白名单");
            return uri;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(error.getMessage() == null ? "请填写可直接下载文件的HTTP或HTTPS地址" : error.getMessage());
        } catch (Exception error) { throw new IllegalArgumentException("下载地址格式无效或域名无法解析"); }
    }

    public Response open(String url, String range, String validator, Set<HttpGet> active) throws Exception {
        URI uri = validate(url);
        for (int hop = 0; hop < 6; hop++) {
            HttpGet request = new HttpGet(uri);
            request.setHeader("Accept-Encoding", "identity"); request.setHeader("User-Agent", "DataEase-Operations/1.0");
            if (range != null) request.setHeader("Range", range);
            if (validator != null && !validator.isBlank()) request.setHeader("If-Range", validator);
            active.add(request);
            ClassicHttpResponse response;
            try { response = client.executeOpen(null, request, null); }
            catch (Exception error) { active.remove(request); request.cancel(); throw error; }
            int status = response.getCode();
            if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                String location = header(response, "Location"); closeResponse(request,response,active);
                if (location.isBlank()) throw new IOException("下载重定向没有目标地址");
                URI next;
                try { next = validate(uri.resolve(location).toString()); }
                catch (IllegalArgumentException rejected) { throw new Rejected("下载重定向指向无效或不允许的来源"); }
                if (uri.getScheme().equals("https") && next.getScheme().equals("http")) throw new IOException("下载重定向不能降低HTTPS保护");
                uri = next; continue;
            }
            return new Response(uri, request, response, active);
        }
        throw new IOException("下载重定向次数过多");
    }

    public byte[] json(String url) throws Exception {
        try (Response r = open(url, null, null, ConcurrentHashMap.newKeySet())) {
            if (r.status() != 200) throw new IOException("应用市场暂时不可用（HTTP " + r.status() + "）");
            byte[] value = r.body().readNBytes(16 * 1024 * 1024 + 1);
            if (value.length > 16 * 1024 * 1024) throw new IOException("应用市场响应超过限制");
            return value;
        }
    }
    public static String header(ClassicHttpResponse response, String name) {
        var header = response.getFirstHeader(name); return header == null ? "" : header.getValue();
    }
    private static String origin(URI uri) { return uri.getScheme() + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port(uri); }
    private static int port(URI uri) { return uri.getPort() < 0 ? (uri.getScheme().equals("https") ? 443 : 80) : uri.getPort(); }
    private static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a = b[0] & 255, c = b[1] & 255;
            return a != 0 && a != 127 && a < 224 && !(a == 100 && c >= 64 && c <= 127)
                    && !(a == 192 && (c == 0 || c == 168)) && !(a == 198 && (c == 18 || c == 19 || c == 51))
                    && !(a == 203 && c == 0) && !(a == 169 && c == 254);
        }
        // Only global unicast IPv6; reject local, mapped, NAT64 and tunnel address spaces.
        return (b[0] & 0xe0) == 0x20 && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x02)
                && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x01
                && ((b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8 || (b[2] & 255) == 0 && (b[3] & 255) == 0));
    }
    @PreDestroy public void close() throws IOException { client.close(); }
    private static void closeResponse(HttpGet request, ClassicHttpResponse response, Set<HttpGet> active) {
        try {
            request.cancel();
            response.close();
        } catch (IOException ignored) {
            // Probes intentionally discard bodies; closing the cancelled socket is expected.
        } finally { active.remove(request); }
    }
    static final class Rejected extends IOException { Rejected(String message) { super(message); } }
    public static final class Response implements AutoCloseable {
        final URI uri; final HttpGet request; final ClassicHttpResponse response; final Set<HttpGet> active;
        Response(URI uri, HttpGet request, ClassicHttpResponse response, Set<HttpGet> active) { this.uri=uri; this.request=request; this.response=response; this.active=active; }
        public int status() { return response.getCode(); }
        public String header(String name) { return DownloadHttp.header(response,name); }
        public URI uri() { return uri; }
        public InputStream body() throws IOException { if (response.getEntity()==null) throw new IOException("下载响应为空"); return response.getEntity().getContent(); }
        public void close() { closeResponse(request,response,active); }
    }
}
