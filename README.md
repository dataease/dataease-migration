# DataEase 运维中心

管理 DataEase 实例的部署、运行和数据保护，支持 V2 迁入已有 V3，或在 V2 服务器上离线新建 V3 后迁入。

## Docker 部署

镜像发布后，安装 Docker 即可部署。首次启动前，在终端输入 8–256 个字符的管理员密码。

```bash
printf '初始管理员密码：'
IFS= read -rs OPERATIONS_ADMIN_PASSWORD
printf '\n'
export OPERATIONS_ADMIN_PASSWORD
```

### Docker Compose（推荐）

安装 Compose 插件，将 [docker-compose.yml](docker-compose.yml) 放到部署目录后启动。Compose 默认使用下方的发布镜像，本地缺少该镜像时会拉取。

```bash
docker compose up -d
```

配置文件支持 Compose v2.0.0 起的 v2 系列和 v5 系列。旧版 Python `docker-compose` v1 不支持端口长格式中的 `host_ip`。可用 `docker compose version` 确认版本。 [Docker Compose 版本说明](https://docs.docker.com/compose/intro/history/) · [v1.29.2 校验规则](https://github.com/docker/compose/blob/1.29.2/compose/config/compose_spec.json#L309-L325)

修改配置后可用 `docker compose config --quiet` 读取文件、展开环境变量并校验最终配置。通过时通常无输出，退出码为 `0`。配置错误时会显示错误，退出码为非零。 [Docker Compose 配置校验](https://docs.docker.com/reference/cli/docker/compose/config/)

从旧的 `operations` 服务切换时，先在原 Compose 项目执行 `docker compose down --remove-orphans`，再启动新版，沿用原项目的数据卷。

### Docker Run

```bash
docker run -d --name dataease-migration \
  -p 127.0.0.1:8080:8080 \
  -e OPERATIONS_ADMIN_PASSWORD \
  -v dataease-migration-data:/opt/apps/data/operations \
  registry.cn-qingdao.aliyuncs.com/dataease/dataease-migration:v1.1
```

使用离线镜像文件时，先用 `docker load -i` 导入。Compose 可用 `OPERATIONS_IMAGE` 指定导入后的镜像名，Docker Run 可替换命令末尾的镜像名。

### 登录与数据持久化

- 默认访问 [http://127.0.0.1:8080](http://127.0.0.1:8080)，使用 `admin` 和初始密码登录。初始化成功后执行 `unset OPERATIONS_ADMIN_PASSWORD`。已有数据卷沿用原密码。Compose 可用 `docker compose up -d --force-recreate` 重建容器，移除容器中的初始密码变量。
- 从内网访问时，在 Compose 部署目录的 `.env` 中设置服务器内网 IP，例如 `OPERATIONS_HOST_ADDRESS=192.168.1.50`。端口可用 `OPERATIONS_HOST_PORT=8087` 修改。使用 `docker run` 时调整 `-p`。
- SQLite、加密凭据、备份和软件包保存在数据卷中。Compose 使用 `operations-data`，上面的 Docker Run 示例使用 `dataease-migration-data`。`docker compose down` 保留卷，`down -v` 会删除卷。备份平台本身时，停服务后备份整个卷。
- 从旧版 JSON 存储升级前，先停服务并备份整个数据目录或卷。新版首次启动会导入原记录到 SQLite。回退时使用升级前的快照。

## 主要功能

- **实例管理**。接入已有部署，或登记空白服务器，之后再安装 DataEase 或从支持自动还原的整体备份重建。卸载 DataEase 后保留实例和连接，状态变为「未安装」。
- **备份与还原**。支持数据、资源和整体备份，可定时备份并导出加密归档。平台部署的原厂企业版可还原到原部署，也可用暂停写入的整体备份和同版本离线包在另一台空服务器重建。异机重建会初始化原厂网关路由，自定义路由和证书需重新配置。外部业务库需单独备份。
- **健康检查与任务**。检查应用、数据库和磁盘，支持定时巡检和结果导出。任务中心可查看执行状态和日志。
- **软件包管理**。上传或通过直链下载安装包、升级包和插件，也可从应用市场获取插件。校验入库后可重复使用。 [DataEase 应用市场](https://apps.fit2cloud.com/dataease)

自动变更适用于受支持的单机 Docker Compose + MySQL 部署，具体操作以预检查结果为准。

新安装支持包含完整配套服务的 V2、V3 企业版离线包，使用内置 MySQL。目标机已有可用 Docker 和 Compose 时直接复用。空白 Linux 服务器在确认安装后可使用匹配的包内 Docker 和 Compose，SSH 需使用 root 账号，目标机需运行 systemd。企业版业务入口由 APISIX 提供，默认端口为 9080，完成验收后可开放。

离线安装的 Docker 和 Compose 程序位于目标机的 `/opt/dataease-operations/docker`，由该主机上的实例共用。实例数据位于各自的安装目录。

企业版新安装沿用原厂 Compose 和默认参数。平台通过独立覆盖配置管理隔离及开放访问。开放后仅发布 APISIX 业务端口，运维中心通过允许 TCP 转发的 SSH 连接访问内置 MySQL。

企业版同主版本离线升级需要当前版本和目标版本的完整离线包。选择目标包时可在向导中补填最低支持升级版本。平台在提交和停服前核对可用空间，再校验整体保护备份并通过独立 Compose 配置切换应用和同步执行器镜像。涉及数据库、网关、浏览器服务、驱动或原厂配置变更，以及已安装插件的环境，使用原厂升级流程。

企业版安装后可在目标机运行 `dectl status`、`dectl version`。开放访问后可使用 `dectl start`、`stop`、`restart` 和 `reload`。`dectl uninstall` 要求确认 Compose 项目名。卸载移除该项目的容器、专用网络和闲置镜像，归档安装目录和数据，保留 Docker、Compose 和共享镜像。

## 迁移 V2 到 V3

迁移支持 v2.10.26 及以上的 V2，使用单机 Docker Compose 和 MySQL。在「版本迁移」选择已接入的 V2 后，可选以下目标。

- **已有 V3**。接入 V3 或填写已安装的 V3 连接。同机部署时，两套实例须使用独立目录、数据库和业务端口。
- **在 V2 服务器上新建 V3**。适用于通过 SSH 接入的单机企业版 V2（内置 MySQL）。选择匹配架构的 V3 企业版离线包、独立安装目录和空闲业务端口。平台复用已保存的 SSH 连接，为 V3 创建独立 Compose 项目和内置 MySQL。V3 的运维命令位于其安装目录的 `bin/dectl`。

输入目标名称确认后，平台自动预检查。迁入已有 V3 时先停止两端应用。若同机新建 V3，则先停止 V2 应用和配套服务，仅保留内置 MySQL。校验 V2 保护备份后才开始安装 V3。两份整体备份均校验通过后，平台覆盖目标数据库和迁移范围内的资源。

V3 先隔离运行，业务验收后可开放访问并按需恢复自动任务。同机新建 V3 完成验收时，平台停止原 V2 的内置 MySQL，保留原安装和数据。已有 V3 覆盖失败时，可从支持自动还原的目标保护备份恢复。同机新建 V3 在数据覆盖前明确失败时，平台会尝试恢复原先运行的 V2 服务。覆盖后失败或执行结果待确认时，按任务提示核对两端状态。企业版保护归档可校验和导出。

验收完成且原 V2 已完全停止时，可在实例列表的「已迁移」筛选中查看原 V2。若运维中心保存了 V2 的初始密码，V3 详情可在管理员验证后核实并查看仍有效的密码。

本地 Excel/CSV 配置和已导入的引擎表随数据库迁移。平台核对两端工作表的行数，并通过 V3 应用的引擎连接执行查询。后续替换或追加时，在 DataEase 中上传新文件。

已实测社区版迁入已有 V3、失败后的保护恢复，以及 x86_64 企业版 v2.10.28 同机迁入离线新建的 v3.2.1。企业授权插件仍需按对应授权环境验收。

## 从源码运行

### Docker 构建

在源码根目录设置初始密码后，先构建本地镜像，再用 Compose 启动。构建使用 Buildx 和 BuildKit，Node.js 和 Maven 在镜像内运行。首次构建需要访问基础镜像、npm 和 Maven 仓库。可在 `docker build` 中用 `--build-arg MAVEN_MIRROR_URL=...` 指定 Maven 镜像。 [Docker BuildKit 文档](https://docs.docker.com/build/buildkit/)

```bash
docker build -t dataease-migration:v1.1 .
export OPERATIONS_IMAGE=dataease-migration:v1.1
docker compose up -d
```

本地已验证 Compose v2.40.3 和 Docker Engine 28.5.2 可完成构建与启动。

### JAR

准备 JDK 17 或以上、Maven、Node.js 22.12 或以上及 npm。Maven 会构建前端并打入 JAR。

```bash
mvn -B -ntp clean package
java -jar target/dataease-migration-1.1.jar
```

数据默认位于 `data/operations`，可通过 `OPERATIONS_DATA_DIRECTORY` 修改。直接在宿主机运行 JAR 时，也可通过本机页面初始化管理员。正式前端位于 [frontend/](frontend/)。
