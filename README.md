# DataEase 运维中心

管理 DataEase 实例的部署、运行和数据保护，支持将 V2 迁移到已有 V3 实例。

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

- **实例管理**。接入已有实例，使用离线包安装和升级，查看运行状态和环境组成。
- **备份与还原**。支持数据、资源和整体备份及还原，可定时备份，也可通过密码保护包的导出和导入进行异机恢复。停服整体备份可用于一致性恢复，外部业务库需单独备份。
- **健康检查与任务**。检查应用、数据库和磁盘，支持定时巡检和结果导出。任务中心可查看执行状态和日志。
- **软件包管理**。上传或通过直链下载安装包、升级包和插件，也可从应用市场获取插件。校验入库后可重复使用。 [DataEase 应用市场](https://apps.fit2cloud.com/dataease)

自动变更适用于受支持的单机 Docker Compose + MySQL 部署，具体操作以预检查结果为准。

## 迁移 V2 到 V3

源端为 V2.10.26 及以上的 V2，目标为已安装的 V3。两端均须是受支持的单机 Docker Compose + MySQL 部署。同机迁移时，两套实例须使用独立的安装目录和数据库，且发布端口不能冲突。

1. 在「实例管理」接入 V2 和 V3。远程实例支持 SSH 密码或私钥，首次连接需核对主机指纹。运维中心必须能直连两端 MySQL。容器中的本地接入需要能访问安装目录和 Docker 守护进程。
2. 在「版本迁移」选择源端和目标端，完成预检查并输入目标实例名称确认。**迁移会覆盖目标库和相关资源。** 平台按需停止两端应用，保持 MySQL 运行，并在写入前创建和校验两端的保护备份。
3. 迁移完成后，在隔离环境验收仪表板、数据集和上传文件，确认后开放访问。同步、定时报告和告警可在验收后按需恢复。
4. 覆盖后失败时需手动恢复。在失败任务中使用该次目标保护备份发起恢复。任务状态待确认时，先按页面提示核查现场。

已实测社区版迁入已有 V3、迁移失败后的保护恢复，以及 V3.2.0 的浏览器业务验收。企业授权插件和正式离线介质仍需按对应发行环境验收。

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
