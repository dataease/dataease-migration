package com.dataease.migration.operations.media;

import com.dataease.migration.operations.app.OperationsService;
import com.dataease.migration.operations.core.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

/** Bounded background transfers. Only fsynced, hashed checkpoints are eligible for resumption. */
@Service
public final class PackageDownloadService {
    private static final long LIMIT = 20L * 1024 * 1024 * 1024, CHECKPOINT = 4L * 1024 * 1024;
    private static final Set<String> ACTIVE = Set.of("queued","checking","downloading","pausing","cancelling","verifying");
    private final OperationsStore store;
    private final CredentialVault vault;
    private final OperationsService operations;
    private final OperationsProperties properties;
    private final DownloadHttp http;
    private final PluginMarketplace market;
    private final ObjectMapper json;
    private final Path root;
    private final Map<String, Transfer> live = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor jobs = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8));
    private volatile boolean closing;

    public PackageDownloadService(OperationsStore store, CredentialVault vault, OperationsService operations,
            OperationsProperties properties, DownloadHttp http, PluginMarketplace market, ObjectMapper json) {
        this.store=store; this.vault=vault; this.operations=operations; this.properties=properties;
        this.http=http; this.market=market; this.json=json;
        root=properties.dataDirectory().resolve("downloads"); OperationsFiles.createPrivateDirectory(root);
    }
    @PostConstruct public synchronized void recover() {
        for (var record : store.list("downloads")) if (ACTIVE.contains(str(record,"state"))) {
            record.put("state","paused"); record.put("status","已暂停"); record.put("speedBytesPerSecond",0);
            record.put("error","服务曾中断，未自动续跑；继续时将核对原文件身份和本地断点，再恢复或去重入库。");
            save(record);
        }
    }
    public Map<String,Object> create(Map<String,Object> input) throws Exception {
        String kind=str(input,"kind"), url=str(input,"url").strip(), label="";
        if ("market".equals(str(input,"source"))) {
            var selected=market.resolve(str(input,"appId"),str(input,"versionId"));
            url=selected.get("url"); label=selected.get("name"); kind="plugin";
        }
        if (!Set.of("package","plugin").contains(kind)) throw bad("请选择安装/升级包或插件包");
        URI uri=validateUrl(url);
        String name=str(input,"fileName").strip();
        if (!name.isEmpty()) name=fileName(name,kind);
        String expected=str(input,"sha256").strip().toLowerCase(Locale.ROOT);
        if (!expected.isBlank() && !expected.matches("[a-f0-9]{64}")) throw bad("SHA-256须为64位十六进制摘要");
        int threads=threads(input.get("threads"));
        synchronized (this) {
        if (closing) throw conflict("服务正在停止，请稍后重试");
        if (store.list("downloads").size() >= 200) throw conflict("下载记录已达200条，请先移除不再需要的历史记录");
        for (var record : store.list("downloads")) if (ACTIVE.contains(str(record,"state"))
                && url.equals(vault.get(str(record,"credentialRef")).get("url"))) {
            if (!kind.equals(str(record,"kind")) || !expected.equals(str(record,"expectedSha256"))
                    || !name.isBlank() && !name.equals(str(record,"fileName")) || threads != number(record,"threads"))
                throw conflict("该地址已有其他配置的下载任务，请先处理原任务");
            return publicView(record);
        }
        String ref=vault.put(Map.of("url",url));
        Map<String,Object> record=new LinkedHashMap<>(); String id=UUID.randomUUID().toString();
        record.putAll(Map.of("id",id,"kind",kind,"fileName",name,"sourceHost",uri.getHost(),"sourceLabel",label.isEmpty()?uri.getHost():label,
                "credentialRef",ref,"threads",threads,"reservedMediaId",UUID.randomUUID().toString(),"expectedSha256",expected));
        record.put("createdAt",now()); record.put("receivedBytes",0L); record.put("totalBytes",-1L);
        record.put("usedThreads",0); record.put("resumable",false); record.put("downloadComplete",false);
        try { enqueue(record); }
        catch (Exception failure) { store.remove("downloads",id); vault.delete(ref); throw failure; }
        return publicView(store.get("downloads",id));
        }
    }
    public synchronized Map<String,Object> action(String id, Map<String,Object> input) throws Exception {
        Map<String,Object> record=store.get("downloads",id); String action=str(input,"action"), state=str(record,"state");
        if (action.equals("pause") || action.equals("cancel")) {
            Transfer t=live.get(id);
            if (t==null) {
                if (!action.equals("cancel") || !Set.of("paused","failed").contains(state)) throw conflict("此下载当前不能暂停或取消");
                discardReservation(record); clearParts(id); record.put("state","cancelled"); record.put("status","已取消"); record.put("receivedBytes",0); record.put("downloadComplete",false); save(record);
            } else {
                synchronized (t) {
                    if (!Set.of("queued","checking","downloading").contains(str(store.get("downloads",id),"state")))
                        throw conflict("下载正在结束或校验入库，请等待状态更新后处理");
                    t.stop.set(action); change(id,Map.of("state",action.equals("pause")?"pausing":"cancelling","status",action.equals("pause")?"暂停中":"取消中"));
                    t.requests.forEach(HttpGet::cancel);
                    if (jobs.remove(t.work)) {
                        try { finishStopped(t); }
                        finally { live.remove(id,t); }
                    }
                }
            }
        } else if (action.equals("resume") || action.equals("restart")) {
            if (closing || live.containsKey(id) || ACTIVE.contains(state)) throw conflict("上一轮下载尚未结束，请等待状态更新");
            if (action.equals("resume") && !Set.of("paused","failed").contains(state)) throw conflict("此记录不需要继续下载");
            if (action.equals("resume") && Boolean.TRUE.equals(record.get("restartRequired"))) throw conflict("原文件或断点已变化，请重新下载");
            String url=str(input,"url").strip();
            if (!url.isBlank()) {
                if (action.equals("resume") && Boolean.TRUE.equals(record.get("downloadComplete")))
                    throw conflict("文件已下载完整，换地址请使用重新下载");
                URI uri=validateUrl(url); String old=str(record,"credentialRef");
                record.put("credentialRef",vault.put(Map.of("url",url))); record.put("sourceHost",uri.getHost());
                save(record); vault.delete(old);
            }
            if (input.containsKey("threads")) record.put("threads",threads(input.get("threads")));
            if (action.equals("restart")) {
                discardReservation(record); clearParts(id); record.put("downloadComplete",false); record.put("receivedBytes",0L); record.remove("sourceIdentity");
                record.remove("restartRequired"); record.remove("sha256");
                record.remove("mediaId"); record.remove("mediaName"); record.remove("mediaStatus");
                record.remove("cleanupPending");
                record.remove("transferNote");
                record.put("totalBytes",-1L); record.put("usedThreads",0); record.put("resumable",false);
                String collection=str(record,"kind").equals("plugin")?"plugins":"packages";
                if (store.find(collection,str(record,"reservedMediaId")).isPresent()) record.put("reservedMediaId",UUID.randomUUID().toString());
            }
            enqueue(record);
        } else throw bad("下载操作无效");
        return publicView(store.get("downloads",id));
    }
    public synchronized void remove(String id) throws Exception {
        var r=store.get("downloads",id);
        if (live.containsKey(id) || ACTIVE.contains(str(r,"state"))) throw conflict("请先取消下载，等待执行结束后再移除记录");
        discardReservation(r); ArchiveSupport.deleteTree(directory(id)); vault.delete(str(r,"credentialRef")); store.remove("downloads",id);
    }
    private void enqueue(Map<String,Object> r) {
        String id=str(r,"id"); Transfer t=new Transfer(id);
        if (live.putIfAbsent(id,t)!=null) throw conflict("该下载仍在执行");
        r.put("state","queued"); r.put("status","排队中"); r.put("error",""); r.put("restartRequired",false); save(r);
        try { jobs.execute(t.work); }
        catch (RejectedExecutionException busy) { live.remove(id); change(id,Map.of("state","paused","status","已暂停","error","下载队列已满，请稍后继续")); throw conflict("下载队列已满，请稍后重试"); }
    }
    private void run(Transfer t) {
        String id=t.id;
        try {
            checkStop(t); var r=store.get("downloads",id); OperationsFiles.createPrivateDirectory(directory(id));
            boolean complete=Boolean.TRUE.equals(r.get("downloadComplete"));
            Metadata meta=null;
            if (!complete) {
                transition(t,"checking","检查下载源");
                String url=vault.get(str(r,"credentialRef")).get("url");
                meta=inspect(url,str(r,"kind"),str(r,"fileName"),!str(r,"expectedSha256").isBlank(),t);
                String previous=str(r,"sourceIdentity");
                boolean partial=number(r,"receivedBytes")>0 || Files.exists(directory(id).resolve("checkpoint.json"));
                if (partial && Boolean.TRUE.equals(r.get("resumable")) && !previous.equals(meta.identity()))
                    throw new Failure("下载源文件已变化，已阻止拼接新旧内容。请重新下载。",true);
                if (!meta.resumable() || !Boolean.TRUE.equals(r.get("resumable")) || !partial) clearParts(id);
                int count=partial && meta.resumable() && Boolean.TRUE.equals(r.get("resumable"))
                        ? (int)number(r,"usedThreads") : meta.resumable() && meta.total()>=2*CHECKPOINT
                                ? (int)Math.min(number(r,"threads"),Math.max(1,meta.total()/CHECKPOINT)) : 1;
                change(id,Map.of("fileName",meta.fileName(),"totalBytes",meta.total(),"usedThreads",count,
                        "resumable",meta.resumable(),"sourceIdentity",meta.identity(),"transferNote",
                        meta.resumable()?"支持断点续传":"下载源不支持可核验的续传，使用单线程；中断后将从头下载"));
                load(t,store.get("downloads",id),meta.resumable());
                long remaining=meta.total()<0?CHECKPOINT:Math.max(0,meta.total()-t.received());
                if (Files.getFileStore(root).getUsableSpace()<remaining*2+256L*1024*1024) throw new Failure("下载盘剩余空间不足，请清理空间后继续",false);
                transition(t,"downloading","下载中");
                transfer(t,meta,count);
                checkStop(t);
                if (meta.total()>=0 && t.received()!=meta.total()) throw new Failure("下载字节数与源文件大小不一致，请重新下载",true);
                if (t.received()==0) throw new Failure("下载文件为空，请核对下载地址",true);
                change(id,Map.of("totalBytes",t.received(),"receivedBytes",t.received(),"downloadComplete",true));
            } else load(t,r,true);
            synchronized (t) { checkStop(t); change(id,Map.of("state","verifying","status","校验入库","speedBytesPerSecond",0)); }
            r=store.get("downloads",id);
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try (InputStream input=parts(t)) { byte[] buffer=new byte[65536]; int n; while((n=input.read(buffer))!=-1) digest.update(buffer,0,n); }
            String sha=HexFormat.of().formatHex(digest.digest());
            if (!str(r,"expectedSha256").isEmpty() && !str(r,"expectedSha256").equals(sha)) throw new Failure("文件SHA-256与提供的摘要不一致，请核对后重新下载",true);
            Map<String,Object> imported;
            try (InputStream input=parts(t)) { imported=operations.addDownloadedPackage(input,str(r,"kind"),str(r,"fileName"),str(r,"reservedMediaId"),sha); }
            change(id,Map.of("state","completed","status","已入库","sha256",sha,"mediaId",imported.get("id"),"mediaStatus",imported.get("status"),
                    "mediaName",Objects.toString(imported.get("name"),imported.get("fileName").toString()),"endedAt",now(),"error",""));
            try { ArchiveSupport.deleteTree(directory(id)); }
            catch (IOException cleanup) { change(id,Map.of("cleanupPending",true)); }
        } catch (Exception failure) {
            String stop=t.stop.get();
            if (!stop.isEmpty()) finishStopped(t);
            else {
                String state=str(store.get("downloads",id),"state");
                String message=failure instanceof Failure own ? own.getMessage()
                        : state.equals("verifying") ? "文件已下载，但软件包校验或入库未完成。请核对类型与文件完整性后重试入库。"
                        : failure instanceof OperationsApiException || failure instanceof DownloadHttp.Rejected ? failure.getMessage()
                        : "连接中断或下载源暂不可用，断点已保留；请稍后继续，链接过期时可更新地址。";
                change(id,Map.of("state","failed","status","失败","error",message,"receivedBytes",actualReceived(t),"speedBytesPerSecond",0,
                        "restartRequired",failure instanceof Failure own && own.restart));
            }
        } finally { t.requests.forEach(HttpGet::cancel); live.remove(id,t); }
    }
    private void finishStopped(Transfer t) {
        if (t.stop.get().equals("cancel")) {
            String cleanupError="";
            try { discardReservation(store.get("downloads",t.id)); clearParts(t.id); }
            catch(IOException cleanup) { cleanupError="下载已停止，临时文件未完全清理，可移除记录重试。"; }
            change(t.id,Map.of("state","cancelled","status","已取消","receivedBytes",0L,"downloadComplete",false,"speedBytesPerSecond",0,"error",cleanupError));
        } else change(t.id,Map.of("state","paused","status","已暂停","receivedBytes",actualReceived(t),"speedBytesPerSecond",0));
    }
    private Metadata inspect(String url,String kind,String supplied,boolean hashBound,Transfer t) throws Exception {
        try (var response=http.open(url,"bytes=0-0",null,t.requests)) {
            int code=response.status(); if (code!=200 && code!=206) throw status(code);
            requireIdentityEncoding(response);
            String type=response.header("Content-Type").toLowerCase(Locale.ROOT);
            if (type.contains("text/html") || type.contains("application/json")) throw new Failure("此地址返回网页或接口内容，请提供软件包文件的直接下载地址",true);
            long total;
            if (code==206) {
                long[] range=range(response.header("Content-Range"));
                if (range[0]!=0 || range[1]!=0) throw new Failure("下载源返回了不正确的分段范围",true);
                total=range[2]; if (response.body().readNBytes(2).length!=1) throw new Failure("下载源分段长度不正确",true);
            } else total=length(response.header("Content-Length"));
            if (total==0 || total>LIMIT) throw new Failure("文件为空或超过20GB上限",true);
            String validator=response.header("ETag");
            boolean resumable=code==206 && total>0 && validator.matches("\"[^\"\\r\\n]{1,512}\"");
            String resource=hashBound?"sha256-bound":resource(response.uri());
            String identity=resumable?hash((resource+":"+validator+":"+total).getBytes(java.nio.charset.StandardCharsets.UTF_8)):"";
            String name=supplied;
            if (name.isBlank()) {
                Matcher utf=Pattern.compile("(?i)filename\\*=UTF-8''([^;]+)").matcher(response.header("Content-Disposition"));
                Matcher basic=Pattern.compile("(?i)filename=\\\"?([^;\\\"]+)").matcher(response.header("Content-Disposition"));
                if (utf.find()) name=URLDecoder.decode(utf.group(1).trim(),java.nio.charset.StandardCharsets.UTF_8);
                else if (basic.find()) name=basic.group(1).trim();
                else name=URLDecoder.decode(response.uri().getPath().substring(response.uri().getPath().lastIndexOf('/')+1),java.nio.charset.StandardCharsets.UTF_8);
            }
            return new Metadata(url,total,resumable,validator,fileName(name,kind),identity,resource,hashBound);
        }
    }
    private void transfer(Transfer t, Metadata meta,int count) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(count); List<Future<?>> futures=new ArrayList<>();
        AtomicReference<Exception> error=new AtomicReference<>();
        try {
            for (int i=0;i<count;i++) { final int part=i; futures.add(pool.submit(()->{
                try { fetchPart(t,meta,part,count); }
                catch(Exception e) { if(error.compareAndSet(null,e)) { t.aborted.set(true); t.requests.forEach(HttpGet::cancel); } }
            })); }
            for (Future<?> future:futures) future.get();
            if (error.get()!=null) throw error.get();
        } finally {
            t.requests.forEach(HttpGet::cancel); pool.shutdown();
            while(!pool.awaitTermination(1,TimeUnit.SECONDS)) { t.requests.forEach(HttpGet::cancel); }
            updateProgress(t);
        }
    }
    private void fetchPart(Transfer t,Metadata meta,int part,int count) throws Exception {
        long begin=meta.total()<0?0:meta.total()*part/count, end=meta.total()<0?-1:meta.total()*(part+1)/count-1;
        long done=t.positions.get(part), wanted=end<0?Long.MAX_VALUE:end-begin+1;
        if (done==wanted) return; checkStop(t);
        String requestRange=meta.resumable()?"bytes="+(begin+done)+"-"+end:null;
        try (FileChannel file=FileChannel.open(part(t.id,part),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var response=http.open(meta.url(),requestRange,meta.resumable()?meta.validator():null,t.requests)) {
            if (response.status()==416) throw new Failure("服务器已无法继续原断点，请重新下载",true);
            if (response.status()!=200 && response.status()!=206) throw status(response.status());
            requireIdentityEncoding(response);
            OperationsFiles.restrictFile(part(t.id,part)); file.position(done);
            if (meta.resumable()) {
                if (response.status()==401 || response.status()==403) throw status(response.status());
                if(response.status()!=206) throw new Failure("下载源未按断点返回分段内容，已阻止拼接。请重新下载。",true);
                long[] actual=range(response.header("Content-Range"));
                if(actual[0]!=begin+done || actual[1]!=end || actual[2]!=meta.total()) throw new Failure("下载源文件大小或分段范围已变化，请重新下载",true);
                if(!response.header("ETag").equals(meta.validator()) || !meta.hashBound() && !resource(response.uri()).equals(meta.resource()))
                    throw new Failure("下载源文件身份已变化，请重新下载",true);
            } else if(response.status()!=200) throw status(response.status());
            MessageDigest sha=MessageDigest.getInstance("SHA-256");
            if(done>0) try(InputStream prefix=Files.newInputStream(part(t.id,part))) { byte[] b=new byte[65536]; long left=done; while(left>0) {int n=prefix.read(b,0,(int)Math.min(left,b.length));if(n<0)throw new IOException();sha.update(b,0,n);left-=n;} }
            long saved=done;
            InputStream input=response.body();
            try {
                byte[] buffer=new byte[65536];int n;
                while((n=input.read(buffer))!=-1) {
                    checkStop(t); if(t.storedBytes.get()+n>LIMIT || done+n>wanted) throw new Failure("下载源返回了超出声明范围的内容",true);
                    ByteBuffer b=ByteBuffer.wrap(buffer,0,n);while(b.hasRemaining()) file.write(b);
                    sha.update(buffer,0,n);done+=n;t.storedBytes.addAndGet(n);t.positions.set(part,done);
                    if(done-saved>=CHECKPOINT) { file.force(true);t.checkpoint(part,done,HexFormat.of().formatHex(((MessageDigest)sha.clone()).digest()));saved=done; }
                }
                if(end>=0 && done!=wanted) throw new IOException("incomplete");
            } finally { file.force(true);t.checkpoint(part,done,HexFormat.of().formatHex(sha.digest())); }
        }
    }
    private void load(Transfer t,Map<String,Object> record,boolean resume) throws Exception {
        int count=Math.max(1,(int)number(record,"usedThreads"));t.positions=new AtomicLongArray(count);
        Map<String,Map<String,Object>> checkpoints;
        try {
            checkpoints=Files.exists(directory(t.id).resolve("checkpoint.json"))
                    ? json.readValue(Files.readAllBytes(directory(t.id).resolve("checkpoint.json")),new TypeReference<>(){}):new LinkedHashMap<>();
        } catch(IOException invalid) { throw new Failure("本地断点记录损坏，请重新下载",true); }
        for(int n=0;n<count;n++) {
            Path part=part(t.id,n);var check=checkpoints.get(String.valueOf(n));long done=resume && check!=null?number(check,"bytes"):0;
            if(done<0) throw new Failure("本地断点超出文件范围，请重新下载",true);
            if(Files.isSymbolicLink(part)) throw new Failure("本地断点文件类型不受支持，请重新下载",true);
            if(done>0 && (!Files.isRegularFile(part) || Files.size(part)<done)) throw new Failure("本地断点文件缺失，请重新下载",true);
            if(Files.exists(part)) try(var file=FileChannel.open(part,StandardOpenOption.WRITE)) { file.truncate(done); }
            if(done>0 && !ArchiveSupport.sha256(part).equals(str(check,"sha256"))) throw new Failure("本地断点校验失败，请重新下载",true);
            long total=number(record,"totalBytes"), limit=total<0?LIMIT:total*(n+1)/count-total*n/count;
            if(done>limit) throw new Failure("本地断点超出文件范围，请重新下载",true);
            t.positions.set(n,done);t.storedBytes.addAndGet(done);
            if(check!=null && resume) t.checkpoints.put(String.valueOf(n),check);
        }
        if(Boolean.TRUE.equals(record.get("downloadComplete")) && t.received()!=number(record,"totalBytes")) throw new Failure("完整下载文件的断点不一致，请重新下载",true);
        updateProgress(t);
    }
    private InputStream parts(Transfer t) throws IOException {
        // Open one segment at a time; failed validation cannot leave a partially opened descriptor set.
        return new InputStream() {
            int index=0; InputStream current;
            public int read() throws IOException { byte[] one=new byte[1]; return read(one,0,1)<0?-1:one[0]&255; }
            public int read(byte[] bytes,int off,int len) throws IOException {
                if(len==0)return 0;
                while(true) { if(current==null) { if(index>=t.positions.length()) return -1; current=Files.newInputStream(part(t.id,index++)); }
                    int n=current.read(bytes,off,len); if(n!=-1)return n; current.close();current=null; }
            }
            public void close() throws IOException { if(current!=null)current.close(); }
        };
    }
    @Scheduled(fixedDelay=1000) public void progress() {
        for(Transfer t:live.values()) if(store.find("downloads",t.id).map(r->str(r,"state").equals("downloading")).orElse(false)) updateProgress(t);
    }
    private void updateProgress(Transfer t) {
        synchronized(t) {
        if(t.positions==null || !store.find("downloads",t.id).map(r->Set.of("checking","downloading").contains(str(r,"state"))).orElse(false))return;
        long now=System.nanoTime(),received=t.received(),elapsed=now-t.lastTime;
        long speed=elapsed>0?(long)((received-t.lastReceived)*1_000_000_000d/elapsed):0;
        t.lastTime=now;t.lastReceived=received;
        change(t.id,Map.of("receivedBytes",received,"speedBytesPerSecond",Math.max(0,speed)));
        }
    }
    @PreDestroy public void close() {
        closing=true;
        for(Transfer t:live.values()) synchronized(t) {
            if(!str(store.get("downloads",t.id),"state").equals("verifying")) { t.stop.set("pause");t.requests.forEach(HttpGet::cancel); }
        }
        jobs.shutdown();
        try { if(!jobs.awaitTermination(30,TimeUnit.SECONDS)) properties.retainLockUntilProcessExit(); }
        catch(InterruptedException e) { properties.retainLockUntilProcessExit();Thread.currentThread().interrupt(); }
    }
    private Path directory(String id) { if(!id.matches("[a-f0-9-]{36}")) throw bad("下载标识无效");return root.resolve(id); }
    private Path part(String id,int index) { return directory(id).resolve("part-"+index); }
    private void clearParts(String id) throws IOException { ArchiveSupport.deleteTree(directory(id)); OperationsFiles.createPrivateDirectory(directory(id)); }
    private void discardReservation(Map<String,Object> record) throws IOException { operations.discardDownloadedPackage(str(record,"kind"),str(record,"reservedMediaId")); }
    private void change(String id,Map<String,?> fields) { synchronized(store){var r=store.get("downloads",id);r.putAll(fields);save(r);} }
    private void save(Map<String,Object> r) { r.put("updatedAt",now());store.put("downloads",str(r,"id"),r); }
    private void transition(Transfer t,String state,String status) throws IOException { synchronized(t){checkStop(t);change(t.id,Map.of("state",state,"status",status));} }
    private long actualReceived(Transfer t) { return t.positions==null?number(store.get("downloads",t.id),"receivedBytes"):t.received(); }
    private static void checkStop(Transfer t) throws IOException { if(!t.stop.get().isEmpty() || t.aborted.get())throw new IOException("stopped"); }
    private static String str(Map<String,?> value,String key) { return Objects.toString(value.get(key),""); }
    private static long number(Map<String,?> value,String key) { return value.get(key) instanceof Number n?n.longValue():0; }
    private static int threads(Object value) { int n=value instanceof Number v?v.intValue():4;if(!Set.of(1,2,4,8).contains(n))throw bad("下载线程数仅支持1、2、4或8");return n; }
    private static long length(String value) { try {return Long.parseLong(value);}catch(Exception ignored){return -1;} }
    private static void requireIdentityEncoding(DownloadHttp.Response response) throws Failure {
        String value=response.header("Content-Encoding");
        if(!value.isBlank() && !value.equalsIgnoreCase("identity")) throw new Failure("下载源未提供原始文件字节，请换用文件直链",true);
    }
    private URI validateUrl(String value) { try{return http.validate(value);}catch(IllegalArgumentException e){throw bad(e.getMessage());} }
    private static String resource(URI uri) {
        String query=Objects.toString(uri.getRawQuery(),"");
        // Known signed URL credentials may rotate while the object key and version remain fixed.
        Set<String> auth=Set.of("x-amz-algorithm","x-amz-credential","x-amz-date","x-amz-expires","x-amz-signedheaders","x-amz-signature","x-amz-security-token","ossaccesskeyid","expires","signature","security-token");
        boolean signed=query.toLowerCase(Locale.ROOT).contains("x-amz-signature=") || query.toLowerCase(Locale.ROOT).contains("ossaccesskeyid=") && query.toLowerCase(Locale.ROOT).contains("signature=");
        if(signed) query=Arrays.stream(query.split("&")).filter(part->!auth.contains(part.split("=",2)[0].toLowerCase(Locale.ROOT))).collect(java.util.stream.Collectors.joining("&"));
        return uri.getScheme()+"://"+uri.getRawAuthority()+uri.getRawPath()+"?"+query;
    }
    private static long[] range(String value) throws Failure { Matcher m=Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(value);if(!m.matches())throw new Failure("下载源分段响应无效",true);try{long a=Long.parseLong(m.group(1)),b=Long.parseLong(m.group(2)),n=Long.parseLong(m.group(3));if(a>b||b>=n||n>LIMIT)throw new Exception();return new long[]{a,b,n};}catch(Exception invalid){throw new Failure("下载源文件大小或分段范围无效",true);} }
    private static String fileName(String name,String kind) {
        if(name.isBlank() || name.length()>240 || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl)) throw bad("无法确定文件名，请填写明确的软件包文件名");
        String lower=name.toLowerCase(Locale.ROOT);
        if(kind.equals("plugin")?!lower.endsWith(".jar"):!lower.matches(".*\\.(zip|tar|tar\\.gz|tgz)$")) throw bad(kind.equals("plugin")?"插件包文件名须以.jar结尾":"安装/升级包仅支持.zip、.tar、.tar.gz或.tgz");
        return name;
    }
    private static Failure status(int code) { return new Failure("下载源返回HTTP "+code+(code==401||code==403?"，链接可能需要授权或已过期，请更新下载地址后继续":"，请检查链接或稍后重试"),false); }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String now() { return Instant.now().toString(); }
    private static OperationsApiException bad(String s) { return OperationsApiException.badRequest(s); }
    private static OperationsApiException conflict(String s) { return OperationsApiException.conflict(s); }
    public static Map<String,Object> publicView(Map<String,Object> record) { var out=new LinkedHashMap<>(record);out.remove("credentialRef");out.remove("reservedMediaId");out.remove("sourceIdentity");return out; }
    private record Metadata(String url,long total,boolean resumable,String validator,String fileName,String identity,String resource,boolean hashBound) { }
    private static final class Failure extends Exception { final boolean restart;Failure(String message,boolean restart){super(message);this.restart=restart;} }
    private final class Transfer {
        final String id;final AtomicReference<String> stop=new AtomicReference<>("");final AtomicBoolean aborted=new AtomicBoolean();final Set<HttpGet> requests=ConcurrentHashMap.newKeySet();
        final Runnable work=()->run(this);
        final AtomicLong storedBytes=new AtomicLong();AtomicLongArray positions;long lastTime=System.nanoTime(),lastReceived;
        final Map<String,Map<String,Object>> checkpoints=new LinkedHashMap<>();
        Transfer(String id){this.id=id;}
        long received(){return storedBytes.get();}
        synchronized void checkpoint(int index,long bytes,String sha) throws IOException {
            checkpoints.put(String.valueOf(index),Map.of("bytes",bytes,"sha256",sha));
            OperationsFiles.writePrivate(directory(id).resolve("checkpoint.json"),json.writeValueAsBytes(checkpoints));
        }
    }
}
