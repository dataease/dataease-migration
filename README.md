# DataEase 数据迁移工具

浏览器访问 `http://localhost:8080`，填写 DataEase 2.0 和 3.0 的 SSH、MySQL 及安装目录信息后执行迁移。

迁移顺序为：

1. 将源端 `data/i18n`、`font`、`exportData`、`map`、`geo`、`appearance` 打包并复制至目标端的 `data` 目录，并将发行包的三个内置插件 JAR 复制至目标端 `data/plugin`。
2. 通过 JAR 内置的 MySQL Connector/J 读取源库的结构、数据、视图、存储过程、函数、触发器及事件。
3. 删除目标端 JDBC URL 指向的数据库，使用 `utf8mb4` 与 `utf8mb4_0900_ai_ci` 重建，并通过 JDBC 写入迁移内容；随后按名称更新飞书多维表格、Apache Hive、达梦插件数据。源端存在其他插件时，任务日志会提示需升级的插件名称。

仅支持 MySQL JDBC URL，例如 `jdbc:mysql://127.0.0.1:3306/dataease`。运行 JAR 的机器必须可通过 JDBC URL 直连源端和目标端 MySQL。填写的数据库用户需具备源库读取定义和数据的权限，以及目标库 `DROP`、`CREATE`、写入权限。

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
