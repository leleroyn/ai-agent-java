#!/usr/bin/env bash
# 生产部署：用 .env 起容器。默认复用已打好的镜像，加 --build 才重新编译打包。
#
#   bash scripts/docker-run.sh                # 用现有镜像（ai-agent-java:latest）启动
#   bash scripts/docker-run.sh --build        # 先调 scripts/docker-build.sh 打版本再启动
#   IMAGE=ai-agent-java:1.0.0 bash scripts/docker-run.sh
#
# 关于目录：容器内 agent 的工作根目录固定为 /workspace（named volume），每个任务实际
# 跑在 /workspace/<taskId>。AGENT_WORKING_DIR 在这里被显式设为 /workspace——.env 里若写
# 相对路径（本地开发习惯的 ./agent-workspace）会在容器里套成 /workspace/agent-workspace，
# 虽能持久化但容易和宿主机路径混淆，所以由部署脚本统一给绝对路径。
set -euo pipefail
cd "$(dirname "$0")/.."

# 关掉 Git Bash(MSYS2) 的参数路径转换。实测在本机 Git Bash 里
#   docker run -e AGENT_WORKING_DIR=/workspace
# 会被改写成 D:/Program Files/Git/workspace；容器内 JVM 把这种值当作相对路径，
# 于是任务文件全部落到 /workspace/D:/Program Files/Git/workspace/<taskId> 这种意外位置。
# 这两个变量只在 MSYS 环境下起作用，Linux/macOS 上是未被使用的普通变量，无副作用。
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

IMAGE="${IMAGE:-ai-agent-java:latest}"
NAME="${NAME:-ai-agent-java}"
PORT="${PORT:-8080}"
ENV_FILE="${ENV_FILE:-.env}"
WORKSPACE_VOLUME="${WORKSPACE_VOLUME:-agent-workspace}"
# 宿主机技能目录（相对仓库根），只读挂到容器 /skills。目录不存在时先建好，
# 否则 bind mount 会按 root 属主自动创建，容易给后续写入埋坑。
SKILLS_HOST_DIR="${SKILLS_HOST_DIR:-agent-skills}"
[ -d "$SKILLS_HOST_DIR" ] || mkdir -p "$SKILLS_HOST_DIR"
# Docker Desktop on Windows 的 bind mount 源必须是 Windows 风格路径（F:/...），
# Git Bash 的 /f/... 不行；而本脚本已禁用 MSYS 自动转换，所以显式取 pwd -W。
# Linux/macOS 上 pwd -W 会失败，退回普通 pwd。
HOST_DIR="$(pwd -W 2>/dev/null || pwd)"
SKILLS_SRC="$HOST_DIR/$SKILLS_HOST_DIR"

if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: $ENV_FILE not found. Copy .env.example to $ENV_FILE and fill it in." >&2
  exit 1
fi

if [ "${1:-}" = "--build" ]; then
  bash scripts/docker-build.sh
elif ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "ERROR: 本地没有镜像 $IMAGE。先跑 bash scripts/docker-build.sh，或用 --build。" >&2
  exit 1
fi

echo "==> 镜像来源信息（构建时写入的 label）："
docker image inspect "$IMAGE" \
  --format '    version={{index .Config.Labels "org.opencontainers.image.version"}}
    commit={{index .Config.Labels "org.opencontainers.image.revision"}}
    built={{index .Config.Labels "org.opencontainers.image.created"}}
    base={{index .Config.Labels "org.opencontainers.image.base.name"}}'

if docker ps -a --format '{{.Names}}' | grep -qx "$NAME"; then
  echo "==> 移除已存在的容器 $NAME"
  docker rm -f "$NAME" >/dev/null
fi

echo "==> 启动 $NAME（宿主端口 $PORT -> 容器 8080，配置来自 $ENV_FILE）"
docker run -d \
  --name "$NAME" \
  --env-file "$ENV_FILE" \
  -e AGENT_WORKING_DIR=/workspace \
  -e AGENT_SKILLS_DIR=/skills \
  -p "${PORT}:8080" \
  -v "${WORKSPACE_VOLUME}:/workspace" \
  -v "${SKILLS_SRC}:/skills:ro" \
  --restart unless-stopped \
  "$IMAGE"

# 启动后立刻校验工作根目录：必须是容器内的绝对 Linux 路径。不校验的话，上面那种
# 路径转换问题只会表现为"文件找不到"，排查成本极高（已经踩过一次）。
actual_wd="$(docker exec "$NAME" printenv AGENT_WORKING_DIR 2>/dev/null || echo)"
echo "==> 技能目录: $SKILLS_SRC -> /skills（只读）"
case "$actual_wd" in
  /*) echo "==> 容器内工作根目录: $actual_wd（绝对路径，正常）" ;;
  *)  echo "ERROR: 容器内 AGENT_WORKING_DIR='$actual_wd' 不是绝对路径。" >&2
      echo "       这通常意味着 shell 对 -e 参数做了 Windows 路径转换（Git Bash/MSYS）；" >&2
      echo "       本脚本已 export MSYS_NO_PATHCONV=1，若仍出现此错，请改用 Linux/macOS shell 或 cmd 执行。" >&2
      docker logs --tail 20 "$NAME" >&2 2>&1 || true
      exit 1 ;;
esac

# 等到健康检查通过再返回；失败就把日志打出来，避免"起了但没起来"这种要人手忙脚乱查。
echo -n "==> 等待就绪"
ready=0
for _ in $(seq 1 45); do
  state="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$NAME" 2>/dev/null || echo gone)"
  case "$state" in
    healthy)  ready=1; break ;;
    none)     sleep 2; continue ;;     # 老镜像没有 HEALTHCHECK，靠 HTTP 判定
    starting) echo -n "."; sleep 2 ;;
    unhealthy) break ;;
    *)        break ;;
  esac
done
echo

if curl -fsS -m 5 "http://localhost:${PORT}/api/v1/agent/health" >/dev/null 2>&1; then
  echo "==> 就绪：$(curl -fsS -m 5 "http://localhost:${PORT}/api/v1/agent/health")"
else
  ready=0
fi

if [ "$ready" != "1" ]; then
  echo "!! 未通过健康检查，最近 40 行日志：" >&2
  docker logs --tail 40 "$NAME" >&2 2>&1 || true
  echo "!! 容器状态：$(docker inspect -f '{{.State.Status}}' "$NAME" 2>/dev/null || echo unknown)" >&2
  exit 1
fi

echo
# 技能挂载结果直接用接口看：/api/v1/agent/skills 返回的就是当前真正加载到的技能名。
skills_now="$(curl -fsS -m 5 "http://localhost:${PORT}/api/v1/agent/skills" 2>/dev/null || echo 未就绪)"
echo "==> 已加载技能: $skills_now"

echo "==> 日志:   docker logs -f $NAME"
echo "==> 停止:   docker stop $NAME        （SIGTERM 优雅停机，正在跑的任务会被租约回收重跑）"
echo "==> 进入:   docker exec -it $NAME bash   # 镜像内有 python/curl/jq/rg，可直接排查"
