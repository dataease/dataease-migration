#!/bin/bash
set -euo pipefail

script=$(readlink -f -- "$0")
root=$(cd -- "$(dirname -- "$script")/.." && pwd -P)
cd -- "$root"
[[ -d .ops-install-claim && -f .ops-runtime/compose-project ]]
project=$(cat .ops-runtime/compose-project)
[[ "$project" =~ ^[a-z0-9][a-z0-9_-]*$ ]]
network="deops_$project"

compose() {
    local -a engine args
    if docker compose version >/dev/null 2>&1; then
        engine=(docker compose)
    else
        engine=(docker-compose)
    fi
    args=(-p "$project" -f docker-compose.yml -f docker-compose-mysql.yml
          -f docker-compose-apisix.yml -f docker-compose-task.yml -f docker-compose-playwright.yml)
    if [[ -f .ops-compose-instance.yml ]]; then args+=(-f .ops-compose-instance.yml); fi
    if [[ -f .ops-compose-release.yml ]]; then args+=(-f .ops-compose-release.yml); fi
    if [[ -f .ops-compose-guard.yml ]]; then args+=(-f .ops-compose-guard.yml); fi
    if [[ "${1:-}" == down && -f .ops-compose-isolated.yml ]]; then
        # Only include the isolation file when its external network is still present.
        if docker network inspect "$network" >/dev/null 2>&1; then args+=(-f .ops-compose-isolated.yml); fi
    fi
    "${engine[@]}" "${args[@]}" "$@"
}

verify_project() {
    local ids id working_dir
    ids=$(docker ps -aq --filter "label=com.docker.compose.project=$project")
    for id in $ids; do
        working_dir=$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' "$id")
        if [[ "$working_dir" != "$root" ]]; then
            printf 'Compose 项目包含其他目录的容器，已停止操作。\n' >&2
            return 1
        fi
    done
}

project_containers() {
    docker ps -aq --no-trunc --filter "label=com.docker.compose.project=$project"
}

verify_runtime_paths() {
    local docker_root docker_path compose_path protected
    docker_root=$(docker info --format '{{.DockerRootDir}}')
    [[ "$docker_root" == /* ]]
    if [[ -e "$docker_root" ]]; then docker_root=$(readlink -f -- "$docker_root"); fi
    docker_path=$(readlink -f -- "$(command -v docker)")
    compose_path=""
    if command -v docker-compose >/dev/null 2>&1; then
        compose_path=$(readlink -f -- "$(command -v docker-compose)")
    fi
    for protected in /opt/dataease-operations /etc/docker /etc/systemd /usr/local/bin /usr/bin \
        /usr/local/lib/docker /usr/lib/docker /var/lib/docker /var/lib/containerd /run/docker /run/containerd \
        "$docker_root" "$docker_path" "$compose_path"; do
        [[ -z "$protected" ]] && continue
        if [[ "$protected" == / || "$root/" == "$protected/"* || "$protected/" == "$root/"* ]]; then
            printf '安装目录与 Docker 运行目录重叠，已停止卸载。\n' >&2
            return 1
        fi
    done
}

cleanup_images() {
    local candidate image reference users tags removed=0 retained=0
    for candidate in "$@"; do
        image=${candidate%%|*}
        reference=${candidate#*|}
        users=$(docker ps -aq --filter "ancestor=$image")
        if [[ -n "$users" ]]; then
            ((retained+=1))
            continue
        fi
        if ! tags=$(docker image inspect -f '{{join .RepoTags "\n"}}' "$image" 2>/dev/null); then continue; fi
        # Deleting by ID avoids deleting a replacement image if a tag was moved.
        if [[ -n "$tags" && "$tags" != "$reference" ]]; then
            ((retained+=1))
        elif docker image rm --no-prune "$image" >/dev/null 2>&1; then
            ((removed+=1))
        else
            ((retained+=1))
        fi
    done
    printf '已清理 %d 个实例镜像，保留 %d 个共享或无法安全移除的镜像。\n' "$removed" "$retained"
}

uninstall() {
    local confirmation archive members link ids id image reference engine_id owned_networks seen=""
    local -a images=()
    read -r -p "输入 Compose 项目名 $project 确认卸载，安装目录将归档: " confirmation
    [[ "$confirmation" == "$project" ]] || return 1
    [[ ! -L .ops-install-claim && ! -L .ops-runtime && ! -L .ops-runtime/compose-project ]]
    verify_runtime_paths
    engine_id=$(docker info --format '{{.ID}}')
    [[ -n "$engine_id" ]]
    verify_project
    ids=$(project_containers)
    for id in $ids; do
        read -r image reference <<< "$(docker container inspect -f '{{.Image}} {{.Config.Image}}' "$id")"
        [[ "$image" =~ ^sha256:[0-9a-f]{64}$ ]]
        if [[ "$seen" != *" $image "* ]]; then
            images+=("$image|$reference")
            seen+=" $image "
        fi
    done
    # Preserve all volumes and use the verified project as the teardown boundary.
    compose down --remove-orphans || [[ -z "$(project_containers)" ]]
    ids=$(project_containers)
    if [[ -n "$ids" ]]; then
        verify_project
        docker stop $ids >/dev/null
        docker rm $ids >/dev/null
    fi
    ids=$(project_containers)
    [[ -z "$ids" ]] || return 1
    if docker network inspect "$network" >/dev/null 2>&1; then
        members=$(docker network inspect -f '{{.Internal}}|{{index .Labels "io.dataease.operations.project"}}|{{len .Containers}}' "$network")
        if [[ "$members" == "true|$project|"* ]]; then
            [[ "$members" == "true|$project|0" ]]
            docker network rm "$network" >/dev/null
        fi
    fi
    owned_networks=$(docker network ls -q --filter "label=com.docker.compose.project=$project")
    for id in $owned_networks; do
        members=$(docker network inspect -f '{{len .Containers}}' "$id")
        [[ "$members" == 0 ]]
        docker network rm "$id" >/dev/null
    done
    cleanup_images "${images[@]}"
    [[ "$(docker info --format '{{.ID}}')" == "$engine_id" ]]
    compose version --short >/dev/null
    umask 077
    archive=$(mktemp -d "$(dirname -- "$root")/.dataease-uninstalled-XXXXXXXX")
    cd -- "$archive"
    mv -- "$root" "$archive/"
    link=/usr/local/bin/dectl
    if [[ -L "$link" && "$(readlink -- "$link")" == "$script" ]]; then rm -- "$link"; fi
    printf 'DataEase 已卸载，Docker 与 Compose 保持可用。\n'
    printf '归档目录: %s\n' "$archive"
}

action=${1:-help}
case "$action" in
    help|-h|--help)
        printf 'dectl status | version | start | stop | restart | reload | uninstall\n'
        ;;
    status)
        verify_project
        compose ps -a
        ;;
    version)
        compose config --images dataease | sed -n 's|^.*/dataease:\(v[23]\.[0-9]*\.[0-9]*\)$|\1|p'
        ;;
    start|stop|restart|reload)
        if [[ ! -f .ops-runtime/cli-open ]]; then
            printf '实例尚未开放访问。请在运维中心完成验收或停止实例。\n' >&2
            exit 1
        fi
        verify_project
        case "$action" in
            stop) compose stop ;;
            restart) compose stop; compose up -d --pull never ;;
            *) compose up -d --pull never ;;
        esac
        ;;
    uninstall)
        uninstall
        ;;
    *)
        printf '不支持的命令。请查看 dectl --help。\n' >&2
        exit 2
        ;;
esac
