# DataEase 数据迁移工具

浏览器访问 `http://localhost:8080`，填写 DataEase 2.0 和 3.0 的服务器地址、MySQL 及安装目录信息后执行完整迁移。远程服务器通过 SSH 操作，本地服务器直接操作本地目录。工具可以直接运行 JAR，也可以构建为 Docker 镜像运行（见下方「使用 Docker 镜像迁移」）。

迁移顺序为：

1. 将源端实际存在的 `data/i18n`、`font`、`exportData`、`map`、`geo`、`appearance`、`static-resource`、`excel` 打包并复制至目标端的 `data` 目录，并将发行包内置的 V3 插件 JAR 复制至目标端 `data/plugin`。启用同步日志开关时，还会迁移 `logs/sync-task/task-handler-log`。不存在的可选目录会跳过；所有候选数据目录都不存在时任务会中止，避免错误安装路径产生空迁移。
2. 优先使用发行包中与当前平台匹配的 `mysql`、`mysqldump` 迁移数据库；未找到可执行工具时，自动回退到 JAR 内置的 MySQL Connector/J，迁移表结构、数据、视图、存储过程、函数、触发器及事件。
3. 删除目标端 JDBC URL 指向的数据库，使用 `utf8mb4` 与 `utf8mb4_0900_ai_ci` 重建并写入迁移内容。
4. 在目标端数据库执行内置的 `upgrade.sql` 升级脚本。
5. 在事务中按模块或名称兼容更新飞书多维表格、Apache Hive、达梦以及同步管理 PostgreSQL 源/目标插件数据。源端存在其他插件时，任务日志会提示需升级的插件名称。

仅支持 MySQL JDBC URL，例如 `jdbc:mysql://127.0.0.1:3306/dataease`。运行 JAR 的机器必须可通过 JDBC URL 直连源端和目标端 MySQL。填写的数据库用户需具备源库读取定义和数据的权限，以及目标库 `DROP`、`CREATE`、写入权限和执行升级脚本中 `ALTER`、`UPDATE`、`INSERT`、`DELETE` 等语句的权限。

源端和目标端安装目录必须填写对应服务器上的非根目录绝对路径；远程文件操作面向 Linux，本地文件操作支持 macOS/Linux。为兼容迁移工具回退到内置 JDBC 的情况，请预先创建一个可连接的全新空目标数据库；迁移开始后该数据库仍会被删除并重建。迁移期间应停止源 V2 的业务写入，并必须停止目标 V3 服务，避免文件快照、数据库数据或目标业务表被并发修改。

迁移任一阶段发生错误都会终止整个任务。页面日志会显示失败阶段、异常类型和底层根因，服务端日志会记录完整异常堆栈。同步任务参数为空、JSON 无效或缺少源/目标数据源对象时，日志还会逐条列出异常任务 ID、名称和具体原因，但不会输出可能包含数据库密码的任务参数原文。失败不会自动回滚已经完成的文件复制、目标库重建或 MySQL DDL，也不会修改 V2 源库；请先按日志修复 V2 数据，再使用全新目标数据库重新执行完整迁移，不要在失败目标库上继续补跑脚本。

## 本地迁移

把源端或目标端服务器地址填写为 `localhost`、`127.x`、`::1` 或运行迁移程序这台机器的网卡地址后，工具会直接归档、解压对应的本地安装目录并复制插件 JAR，不会建立 SSH 连接；SSH 端口、用户名和密码可以留空。远程地址仍会通过 SSH 执行相同操作，并要求填写有效的 SSH 配置。

源端和目标端会分别判断，因此支持本地到本地、本地到远程、远程到本地和远程到远程。连接方式不会改变迁移内容，四种组合都会依次执行服务文件迁移、数据库结构及数据复制、`upgrade.sql`、通用插件数据更新、同步管理专项数据转换和同步 PostgreSQL 插件数据更新。直接操作本地文件目前适用于 macOS/Linux，并要求本机提供 `/bin/sh` 和 `tar`。

## 使用 Docker 镜像迁移

工程提供 `Dockerfile`，可将迁移工具构建为镜像并以容器方式运行。镜像基于 `alpine-openjdk21-jre`，内置 JAR 与当前目标架构匹配的 Linux MySQL 客户端工具（`/opt/apps/tools/mysql`），通过 HTTP 提供与本地运行一致的控制台页面。

### 构建镜像

先在 `target/` 下准备好 JAR（执行 `mvn package`），再构建：

```bash
# 单架构（当前 buildx 默认目标架构）
docker build -t dataease-migration .

# 多架构（amd64 + arm64）
docker buildx build --platform linux/amd64,linux/arm64 -t dataease-migration --push .
```

构建时会按目标架构（`TARGETARCH`）自动挑选匹配的 Linux 客户端工具：`amd64` 使用 `linux-x64`，`arm64` 使用 `linux-arm64`；macOS/Windows 工具不会被打入镜像。被选中的工具需已放入 `tools/mysql/<平台>/bin`，否则容器内仍会回退到内置 JDBC 迁移。

### 运行容器

```bash
docker run -d --name migration \
  -p 8080:8080 \
  -e SERVER_PORT=8080 \
  dataease-migration
```

运行后浏览器访问 `http://localhost:8080` 即可填写源端/目标端信息执行迁移。

### 参数说明

通过 `-e` 传入的环境变量与直接运行 JAR 时的参数一一对应：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | 控制台 HTTP 服务端口，需与 `-p` 映射一致 |
| `MIGRATION_COPY_SYNC_TASK_LOGS` | `false` | 是否复制同步任务物理日志 |
| `MIGRATION_MYSQL_TOOLS_DIRECTORY` | `/opt/apps/tools/mysql` | MySQL 客户端工具根目录（镜像内置，一般无需改动） |

示例（开启同步日志复制并改用 8018 端口）：

```bash
docker run -d --name migration \
  -p 8018:8018 \
  -e SERVER_PORT=8018 \
  -e MIGRATION_COPY_SYNC_TASK_LOGS=true \
  dataease-migration
```

> 镜像内已内置与目标架构匹配的 MySQL 客户端工具，容器可直接使用原生 `mysql`/`mysqldump` 迁移，无需在宿主机安装 MySQL 客户端；只有当 `TARGETARCH` 对应平台的 `tools` 未随构建打入时，才回退到 JDBC 迁移。

## 同步任务日志复制开关

同步任务物理日志可能达到数百 MB 甚至更大，因此默认不复制。需要迁移时，在启动 JAR 时添加参数：

```bash
java -jar dataease-migration-1.0.0.jar --migration.files.copy-sync-task-logs=true
```

也可以使用环境变量：

```bash
MIGRATION_COPY_SYNC_TASK_LOGS=true java -jar dataease-migration-1.0.0.jar
```

开启后会把源端 `${安装目录}/logs/sync-task/task-handler-log` 单独归档，并合并解压至目标端同名目录，不会复制其他应用日志。日期目录和以 `per_sync_task_log.id` 命名的 `.log` 文件会保持不变；源端目录不存在时会记录日志并安全跳过。该配置是启动级参数，修改后需要重启迁移程序。

## 本机数据库测试

源库和目标库可以位于同一个本机 MySQL，但数据库名必须不同，例如：

- 源库：`jdbc:mysql://127.0.0.1:3306/dataease_v2_test`
- 目标库：`jdbc:mysql://127.0.0.1:3306/dataease_v3_test`

请先把待测数据导入源库并创建可连接的空目标库。目标库用户需要具备 `DROP`、`CREATE`、写入以及升级脚本所需的 `ALTER`、`UPDATE`、`INSERT`、`DELETE` 权限；执行测试时目标库仍会被删除并重建。若要验证 JDBC 批量复制优化，请确保本地 `tools/mysql/<平台>/bin` 中没有可用的 `mysql` 和 `mysqldump`，否则工具会优先走原生导出/导入路径。

## 自动选择数据库迁移工具

应用启动时按当前操作系统和 CPU 架构，在 `tools/mysql/<平台>/bin` 查找 `mysql` 和 `mysqldump`。两个工具均存在且可执行时，自动使用本地工具迁移；否则自动使用 JAR 内置 JDBC 迁移。

| 操作环境 | 工具目录 |
| --- | --- |
| macOS Apple Silicon | `tools/mysql/macos-arm64/bin` |
| macOS Intel | `tools/mysql/macos-x64/bin` |
| Linux ARM64 | `tools/mysql/linux-arm64/bin` |
| Linux x64 | `tools/mysql/linux-x64/bin` |
| Windows ARM64 | `tools/mysql/windows-arm64/bin` |
| Windows x64 | `tools/mysql/windows-x64/bin` |

Windows 工具名称为 `mysql.exe`、`mysqldump.exe`；其他平台为 `mysql`、`mysqldump`，并需具备可执行权限。Windows ARM64 依次尝试 `windows-arm64` 和 `windows-x64`，以支持 x64 仿真。

工程已提供上述目录结构。将与目标系统相符的官方 MySQL 客户端二进制及其所依赖的运行库放入对应 `bin` 目录，随后连同 JAR 和 `tools` 目录一起发布。MySQL 客户端的下载、再分发及许可责任须遵守 Oracle/MySQL 的适用条款。可通过环境变量 `MIGRATION_MYSQL_TOOLS_DIRECTORY` 修改工具根目录。没有本地工具时，远端服务器也无需安装 MySQL 客户端命令。

执行 `mvn package` 会同时生成 `target/dataease-migration-1.0.0-distribution.zip`。该发行包包含 JAR、`tools`、`plugins` 目录和 README；在打包前放入的客户端二进制也会一起包含。

```bash
mvn spring-boot:run
```
