package com.dataease.migration.operations.app;

import java.util.*;

/** Remediation is attached to known checks; it never changes a check result or grants execution. */
final class CheckGuidance {
    private CheckGuidance() {}

    static Map<String, Object> apply(Map<String, Object> row) {
        if (Set.of("passed", "通过", "正常").contains(String.valueOf(row.get("status")))) return row;
        String title = String.valueOf(row.get("title"));
        String[] guidance =
                switch (title) {
                    case "源环境连接", "源环境识别" ->
                            new String[] {
                                "connection", "source", "维护源端连接", "核对源实例的服务器、安装目录及数据库认证，再重新检查。"
                            };
                    case "目标连接", "目标环境识别", "元数据库连接" ->
                            new String[] {
                                "connection",
                                "target",
                                "维护目标连接",
                                "核对目标服务器和数据库连接。已接入实例可更新保存的认证，未接入目标需返回配置页修改。"
                            };
                    case "源端备份覆盖" ->
                            new String[] {
                                "environment",
                                "source",
                                "检查源端环境组成",
                                "确认数据引擎与数据库的实际组成。独立引擎或多节点未纳入可验证保护前，不能继续变更。"
                            };
                    case "目标保护覆盖" ->
                            new String[] {
                                "environment",
                                "target",
                                "检查目标环境组成",
                                "目标必须能建立完整保护备份。请核对引擎和部署组成，不能用资源备份替代完整保护。"
                            };
                    case "离线介质兼容性", "隔离启动执行约束" ->
                            new String[] {
                                "media",
                                "",
                                "重新选择离线包",
                                "选择与用途、版本和服务器架构匹配的已校验离线包；不执行无法保证离线与隔离的原始安装脚本。"
                            };
                    case "迁移兼容性", "恢复点与目标兼容性", "目标主机隔离", "未接入目标去重", "演练隔离", "原服务器状态" ->
                            new String[] {
                                "target",
                                "",
                                "重新选择目标",
                                "核对目标类型、版本及独立数据库。恢复演练不能覆盖备份来源实例，迁移也不能覆盖源端自身。"
                            };
                    case "目标身份", "新目标运行环境", "目标主机身份" ->
                            new String[] {
                                "configure", "", "修改目标配置", "核对服务器地址、连接方式和安装目录；目标需满足受支持的单机部署条件。"
                            };
                    case "代表性业务查询" ->
                            new String[] {
                                "query",
                                "",
                                "维护验收查询",
                                "配置能够实际返回业务样例的只读查询，并重新检查；不能用 SELECT 1 替代业务核对。"
                            };
                    case "数据库自动任务", "数据库自动任务暂停" ->
                            new String[] {
                                "manual", "", "", "请由数据库管理员停用启用中的 MySQL 事件，并确认任务已经结束，再重新检查。"
                            };
                    case "隔离与自动任务保护", "实际网络与访问隔离", "自动任务强制暂停", "隔离编排支持" ->
                            new String[] {
                                "manual", "", "", "核对容器网络、端口及应用调度隔离。保持业务入口关闭，处理后重新检查；不能直接启动或跳过隔离。"
                            };
                    case "目录权限与可用空间", "磁盘与资源目录" ->
                            new String[] {
                                "manual", "", "", "核对安装目录的读取权限和实际可用空间。清理前确认文件用途，避免删除数据库卷或保护备份。"
                            };
                    default -> null;
                };
        if (guidance != null) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.put(
                    "remediation",
                    Map.of(
                            "type",
                            guidance[0],
                            "endpoint",
                            guidance[1],
                            "label",
                            guidance[2],
                            "hint",
                            guidance[3]));
            return copy;
        }
        return row;
    }
}
