package com.dataease.migration.operations.exec;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import jakarta.servlet.Filter;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Loaded in the target DataEase JVM, without changing its application JAR or database jobs. */
public final class ApplicationIsolationGuard
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    public static final String ENDPOINT = "/__deops_guard";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.addBeanFactoryPostProcessor(factory -> {
            if (factory.containsSingleton("deopsIsolationGuard")) return;
            // The vendor starter calls Scheduler.start() itself after its configured delay.
            BeanDefinitionRegistry registry = (BeanDefinitionRegistry) factory;
            for (String name : registry.getBeanDefinitionNames()) {
                var definition = registry.getBeanDefinition(name);
                if ("org.springframework.context.annotation.internalScheduledAnnotationProcessor".equals(name)
                        || "schedulerStarter".equals(definition.getFactoryMethodName())
                        || "com.fit2cloud.quartz.SchedulerStarter"
                                .equals(definition.getBeanClassName())) {
                    registry.removeBeanDefinition(name);
                }
            }
            GuardState state = new GuardState(context);
            factory.addBeanPostProcessor(new PausedSchedulers(state));
            StatusServlet status = new StatusServlet(state);
            Filter filter = (request, response, chain) -> status.service(request, response);
            var registration = new FilterRegistrationBean<>(filter);
            registration.addUrlPatterns(ENDPOINT);
            registration.setOrder(Integer.MIN_VALUE);
            registration.setName("deopsIsolationGuard");
            factory.registerSingleton("deopsIsolationGuard", registration);
        });
    }

    public static final class PausedSchedulers implements BeanPostProcessor {
        private final GuardState state;
        PausedSchedulers(GuardState state) { this.state = state; }
        @Override
        public Object postProcessBeforeInitialization(Object bean, String name) {
            for (Class<?> type = bean.getClass(); type != null; type = type.getSuperclass()) {
                if ("org.springframework.scheduling.quartz.SchedulerFactoryBean".equals(type.getName())) {
                    try {
                        type.getMethod("setAutoStartup", boolean.class).invoke(bean, false);
                    } catch (ReflectiveOperationException error) {
                        throw new IllegalStateException("Cannot enforce scheduler standby", error);
                    }
                }
            }
            return bean;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
            for (Class<?> contract : bean.getClass().getInterfaces()) {
                if (!"org.quartz.Scheduler".equals(contract.getName())) continue;
                state.register(bean, contract);
                return Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                        (proxy, method, args) -> {
                            if (List.of("start", "startDelayed", "triggerJob", "resumeJob",
                                    "resumeJobs", "resumeTrigger", "resumeTriggers", "resumeAll")
                                    .contains(method.getName())) {
                                throw new IllegalStateException("Operations acceptance keeps scheduling paused");
                            }
                            try {
                                return method.invoke(bean, args);
                            } catch (InvocationTargetException error) {
                                throw error.getCause();
                            }
                        });
            }
            return bean;
        }
    }

    /** In-memory grants deliberately expire when the native application restarts. */
    public static final class GuardState {
        static final String TOKEN_FILE = "/tmp/.deops-guard-token";
        static final String LISTENER = "deops-allowlist-v2";
        final ConfigurableApplicationContext context;
        final ObjectMapper json = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        final String epoch = UUID.randomUUID().toString();
        final String token;
        final Map<String, Object> schedulers = new TreeMap<>();
        volatile Set<String> allowed = Set.of();
        final Set<String> guardPaused = new HashSet<>();

        GuardState(ConfigurableApplicationContext context) {
            this.context = context;
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            try {
                Path file = Path.of(TOKEN_FILE);
                // Never follow an existing symlink, including one left by another process.
                Files.deleteIfExists(file);
                Files.createFile(file, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
                Files.writeString(file, token);
            } catch (IOException error) { throw new IllegalStateException("Cannot create scheduler control capability", error); }
        }

        synchronized void register(Object scheduler, Class<?> contract) {
            try {
                String name = String.valueOf(call(scheduler, "getSchedulerName"));
                if (schedulers.putIfAbsent(name, scheduler) != null)
                    throw new IllegalStateException("Duplicate scheduler identity");
                Class<?> listener = Class.forName("org.quartz.TriggerListener", false, contract.getClassLoader());
                Object gate = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener},
                        (proxy, method, args) -> {
                            if ("getName".equals(method.getName())) return LISTENER;
                            if ("vetoJobExecution".equals(method.getName())) {
                                try {
                                    Object detail = call(args[1], "getJobDetail");
                                    Object key = call(detail, "getKey");
                                    return !licensed(detail) || !allowed.contains(grant(name, scheduler, key));
                                } catch (Exception unsafe) { return true; }
                            }
                            if ("toString".equals(method.getName())) return LISTENER;
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return proxy == args[0];
                            return null;
                        });
                Object manager = call(scheduler, "getListenerManager");
                Class<?> matcher = Class.forName("org.quartz.impl.matchers.EverythingMatcher", false, contract.getClassLoader());
                Object all = matcher.getMethod("allTriggers").invoke(null);
                invoke(manager, "addTriggerListener", gate, List.of(all));
                call(scheduler, "standby");
            } catch (Exception error) { throw new IllegalStateException("Cannot enforce scheduling allowlist", error); }
        }

        static Object call(Object target, String name, Object... args) throws Exception { return invoke(target, name, args); }
        static Object invoke(Object target, String name, Object... args) throws Exception {
            for (var method : target.getClass().getMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
                boolean matches = true;
                Class<?>[] types = method.getParameterTypes();
                for (int n = 0; n < args.length; n++)
                    if (args[n] != null && !types[n].isInstance(args[n])) matches = false;
                if (!matches) continue;
                try { return method.invoke(target, args); }
                catch (InvocationTargetException failure) {
                    if (failure.getCause() instanceof Exception error) throw error;
                    throw failure;
                }
            }
            throw new NoSuchMethodException(name);
        }
        String digest(Object value) throws Exception {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));
        }
        String key(Object key) throws Exception { return call(key, "getGroup") + "/" + call(key, "getName"); }
        String category(Object detail) throws Exception {
            String type = ((Class<?>) call(detail, "getJobClass")).getName();
            String group = String.valueOf(call(call(detail, "getKey"), "getGroup"));
            if (type.equals("io.dataease.job.schedule.ExtractDataJob")) return "sync";
            if (type.equals("io.dataease.job.schedule.DeXpackDataSyncTaskScheduleJob")
                    && Set.of("SYNC_TASK", "SYNC_SIMPLE_TASK").contains(group)) return "sync";
            if (type.equals("io.dataease.job.schedule.DeXpackScheduleJob")) {
                if (group.equals("REPORT_TASK")) return "reports";
                if (group.equals("THRESHOLD_TASK")) return "alerts";
            }
            return "unsupported";
        }
        boolean licensed(Object detail) {
            try {
                if (((Class<?>) call(detail, "getJobClass")).getName().equals("io.dataease.job.schedule.ExtractDataJob")) return true;
                Class<?> license = Class.forName("io.dataease.license.utils.LicenseUtil", false, context.getClassLoader());
                if (!Boolean.TRUE.equals(license.getMethod("licenseValid").invoke(null))
                        || Boolean.FALSE.equals(license.getMethod("validate").invoke(null))) return false;
                String group = String.valueOf(call(call(detail, "getKey"), "getGroup"));
                String bean = Set.of("SYNC_TASK", "SYNC_SIMPLE_TASK").contains(group)
                        ? "dataSyncTaskExecutorInteractImpl" : "xpackTaskExecutorInteractImpl";
                Class<?> factory = Class.forName("io.dataease.utils.CommonBeanFactory", false, context.getClassLoader());
                return factory.getMethod("getBean", String.class).invoke(null, bean) != null;
            } catch (Exception unavailable) { return false; }
        }
        List<Object> keys(Object scheduler) throws Exception {
            Class<?> matcher = Class.forName("org.quartz.impl.matchers.GroupMatcher", false, context.getClassLoader());
            return new ArrayList<>((Set<?>) call(scheduler, "getJobKeys", matcher.getMethod("anyJobGroup").invoke(null)));
        }
        List<Object> triggers(Object scheduler, Object key) throws Exception {
            return new ArrayList<>((List<?>) call(scheduler, "getTriggersOfJob", key));
        }
        String definition(Object scheduler, Object key) throws Exception {
            Object detail = call(scheduler, "getJobDetail", key);
            // Vendor annotation jobs can carry Spring beans/method invokers in their data map.
            // They are never eligible; do not serialize their object graph or configuration.
            if (category(detail).equals("unsupported"))
                return digest(List.of(key(key), ((Class<?>) call(detail, "getJobClass")).getName()));
            List<Object> plans = new ArrayList<>();
            for (Object trigger : triggers(scheduler, key)) {
                Map<String, Object> plan = new TreeMap<>();
                plan.put("key", key(call(trigger, "getKey")));
                for (String field : List.of("StartTime", "EndTime", "MisfireInstruction", "CronExpression", "TimeZone", "RepeatInterval", "RepeatCount")) {
                    try { plan.put(field, String.valueOf(call(trigger, "get" + field))); }
                    catch (NoSuchMethodException ignored) { }
                }
                plan.put("data", call(trigger, "getJobDataMap"));
                plans.add(plan);
            }
            plans.sort(Comparator.comparing(Object::toString));
            return digest(List.of(key(key), ((Class<?>) call(detail, "getJobClass")).getName(), call(detail, "getJobDataMap"), plans));
        }
        String grant(String name, Object scheduler, Object key) throws Exception {
            return digest(List.of(name, key(key), definition(scheduler, key)));
        }
        synchronized Map<String, Object> inventory() throws Exception {
            if (schedulers.size() != 1 || context.getBean(ApplicationAvailability.class).getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC)
                throw new IllegalStateException("应用尚未就绪或包含不支持的多个调度器");
            Class<?> repo = Class.forName("org.quartz.impl.SchedulerRepository", false, context.getClassLoader());
            Collection<?> actual = (Collection<?>) repo.getMethod("lookupAll").invoke(repo.getMethod("getInstance").invoke(null));
            // Spring may intentionally unbind its SchedulerFactoryBean scheduler from this
            // repository (exposeSchedulerInRepository=false). Extra repository schedulers fail.
            if (!schedulers.values().containsAll(actual))
                throw new IllegalStateException("存在未受管调度器");
            List<Map<String, Object>> jobs = new ArrayList<>();
            boolean paused = true;
            for (var entry : schedulers.entrySet()) {
                Object scheduler = entry.getValue();
                if (Boolean.TRUE.equals(call(scheduler, "isShutdown"))
                        || call(call(scheduler, "getListenerManager"), "getTriggerListener", LISTENER) == null)
                    throw new IllegalStateException("调度保护未生效");
                paused &= Boolean.TRUE.equals(call(scheduler, "isInStandbyMode"))
                        && ((List<?>) call(scheduler, "getCurrentlyExecutingJobs")).isEmpty();
                for (Object key : keys(scheduler)) {
                    if (jobs.size() >= 1000) throw new IllegalStateException("任务超过1000项，请先在DataEase缩小任务范围");
                    Object detail = call(scheduler, "getJobDetail", key);
                    List<Object> triggers = triggers(scheduler, key);
                    String category = category(detail), id = digest(List.of(entry.getKey(), key(key)));
                    boolean available = !category.equals("unsupported") && !triggers.isEmpty() && licensed(detail);
                    String reason = available ? "" : category.equals("unsupported") ? "此类任务暂不支持恢复" : triggers.isEmpty() ? "没有可运行的计划" : "企业插件或有效授权未就绪";
                    Date next = null;
                    for (Object trigger : triggers) {
                        try { call(trigger, "getCronExpression"); }
                        catch (NoSuchMethodException notCron) {
                            if (!Integer.valueOf(-1).equals(call(trigger, "getRepeatCount"))) {
                                available = false; reason = "一次性或有限次数计划暂不支持恢复，请先在DataEase处理";
                            }
                        }
                        String state = String.valueOf(call(scheduler, "getTriggerState", call(trigger, "getKey")));
                        if (!Set.of("NORMAL", "BLOCKED").contains(state) && !(state.equals("PAUSED") && guardPaused.contains(id))) {
                            available = false; reason = "业务任务已暂停、计划已结束或触发器异常，请先在DataEase处理";
                        }
                        Date date = (Date) call(trigger, "getFireTimeAfter", new Date());
                        if (date == null) { available = false; reason = "计划已结束"; }
                        else if (next == null || date.before(next)) next = date;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", id); row.put("taskId", String.valueOf(call(key, "getName")));
                    row.put("group", String.valueOf(call(key, "getGroup"))); row.put("category", category);
                    row.put("definition", definition(scheduler, key)); row.put("available", available);
                    row.put("reason", reason); row.put("enabled", available && !Boolean.TRUE.equals(call(scheduler, "isInStandbyMode")) && allowed.contains(grant(entry.getKey(), scheduler, key)));
                    row.put("nextRunAt", next == null ? "" : next.toInstant().toString());
                    jobs.add(row);
                }
            }
            jobs.sort(Comparator.comparing(row -> String.valueOf(row.get("id"))));
            return Map.of("guardVersion", 2, "epoch", epoch, "schedulersPaused", paused,
                    "snapshot", digest(List.of(epoch, jobs.stream().map(row -> List.of(row.get("id"), row.get("definition"), row.get("available"), row.get("enabled"))).toList())), "jobs", jobs);
        }
        synchronized Map<String, Object> apply(Map<String, Object> input) throws Exception {
            if (Boolean.TRUE.equals(input.get("revoke"))) {
                if (!(input.get("selected") instanceof List<?> selected) || !selected.isEmpty())
                    throw new IllegalArgumentException("撤销只能暂停全部后续调度");
                allowed = Set.of();
                for (Object scheduler : schedulers.values()) call(scheduler, "standby");
                return inventory();
            }
            Map<String, Object> before = inventory();
            if (!Objects.equals(before.get("snapshot"), input.get("snapshot"))) throw new IllegalArgumentException("任务或运行状态已变化，请刷新后重新确认");
            if (!(input.get("selected") instanceof List<?> selected) || selected.size() > 1000
                    || selected.stream().anyMatch(value -> !(value instanceof String))) throw new IllegalArgumentException("请选择明确的任务");
            Set<?> ids = new HashSet<>(selected);
            var rows = (List<Map<String, Object>>) before.get("jobs");
            if (rows.stream().filter(row -> ids.contains(row.get("id")) && Boolean.TRUE.equals(row.get("available"))).count() != ids.size())
                throw new IllegalArgumentException("所选任务不存在、已停用或尚不支持恢复");
            allowed = Set.of();
            try {
                for (Object scheduler : schedulers.values()) call(scheduler, "standby");
                for (Object scheduler : schedulers.values())
                    if (!((List<?>) call(scheduler, "getCurrentlyExecutingJobs")).isEmpty())
                        throw new IllegalStateException("有任务仍在执行；后续调度已暂停，等待执行结束后重新检查");
                Set<String> grants = new HashSet<>();
                for (var entry : schedulers.entrySet()) {
                    Object scheduler = entry.getValue();
                    for (Object key : keys(scheduler)) {
                        String id = digest(List.of(entry.getKey(), key(key)));
                        if (!ids.contains(id)) {
                            if (triggers(scheduler, key).stream().allMatch(trigger -> {
                                try { return !"PAUSED".equals(String.valueOf(call(scheduler, "getTriggerState", call(trigger, "getKey")))); }
                                catch (Exception error) { return false; }
                            })) guardPaused.add(id);
                            call(scheduler, "pauseJob", key);
                            continue;
                        }
                        // Rebase the next firing to the future, never replay accumulated missed runs.
                        for (Object trigger : triggers(scheduler, key)) {
                            Date old = (Date) call(trigger, "getStartTime");
                            Date start = new Date(Math.max(old.getTime(), System.currentTimeMillis() + 1000));
                            Object builder = call(trigger, "getTriggerBuilder");
                            call(builder, "startAt", start);
                            call(scheduler, "rescheduleJob", call(trigger, "getKey"), call(builder, "build"));
                        }
                        call(scheduler, "resumeJob", key);
                        guardPaused.remove(id);
                        grants.add(grant(entry.getKey(), scheduler, key));
                    }
                }
                allowed = Set.copyOf(grants);
                if (!ids.isEmpty()) for (Object scheduler : schedulers.values()) call(scheduler, "start");
                return inventory();
            } catch (Exception failure) {
                allowed = Set.of();
                for (Object scheduler : schedulers.values()) call(scheduler, "standby");
                throw failure;
            }
        }
    }

    public static final class StatusServlet extends HttpServlet {
        private final GuardState state;
        StatusServlet(GuardState state) { this.state = state; }
        private boolean local(HttpServletRequest request) {
            return List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr());
        }
        @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (!local(request)) { response.sendError(404); return; }
            try { respond(response, 200, state.inventory()); }
            catch (Exception unavailable) {
                respond(response, 503, Map.of("guardVersion", 2, "schedulersPaused", false,
                        "reason", unavailable instanceof IllegalStateException ? unavailable.getMessage()
                                : "调度状态无法完整核验（" + unavailable.getClass().getSimpleName() + "）"));
            }
        }
        @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
            if (!local(request) || !MessageDigest.isEqual(state.token.getBytes(StandardCharsets.UTF_8),
                    Objects.toString(request.getHeader("X-DEOPS-Guard"), "").getBytes(StandardCharsets.UTF_8))) {
                response.sendError(404); return;
            }
            try {
                byte[] bytes = request.getInputStream().readNBytes(128 * 1024 + 1);
                if (bytes.length > 128 * 1024) throw new IllegalArgumentException("任务清单超过限制");
                respond(response, 200, state.apply(state.json.readValue(bytes, Map.class)));
            } catch (Exception failure) {
                // Reflective exceptions can contain vendor configuration or task payloads.
                String message = failure instanceof IllegalArgumentException || failure instanceof IllegalStateException
                        ? failure.getMessage() : "恢复未完成，调度器已保持暂停，请刷新状态后重新检查";
                respond(response, 409, Map.of("message", message));
            }
        }
        private void respond(HttpServletResponse response, int status, Map<String, Object> body) throws IOException {
            response.setStatus(status); response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8"); state.json.writeValue(response.getWriter(), body);
        }
    }
}
