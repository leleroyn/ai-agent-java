#!/usr/bin/env bash
# 生产部署：用 .env 起容器。默认复用已打好的镜像，加 --build 才重新编译打包。
#
#   bash scripts/docker-run.sh                # 用现有镜像（ai-agent-java:latest）启动
#   bash scripts/docker-run.sh --build        # 先调 scripts/docker-build.sh 打版本再启动
#   IMAGE=ai-agent-java:1.0.0 bash scripts/docker-run.sh
#
# 关于目录：容器内 agent 的工作根目录固定为 /workspace（bind mount 到宿主机目录），每个任务实际
# 跑在 /workspace/<taskId>。AGENT_WORKING_DIR 在这里被显式设为 /workspace——.env 里若写
# 相对路径（本地开发习惯的 ./agent-workspace）会在容器里套成 /workspace/agent-workspace，
# 虽能持久化但容易和宿主机路径混淆，所以由部署脚本统一给绝对路径。
#
# 宿主机 workspace 目录：默认 $HOST_DIR/agent-workspace（即项目目录/agent-workspace），
# 部署到 203 后直接在 /data/ai-agent/agent-workspace/<taskId>/ 就能看到任务产物。
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
# Docker Desktop on Windows 的 bind mount 源必须是 Windows 风格路径（F:/...），
# Git Bash 的 /f/... 不行；而本脚本已禁用 MSYS 自动转换，所以显式取 pwd -W。
# Linux/macOS 上 pwd -W 会失败，退回普通 pwd。
HOST_DIR="$(pwd -W 2>/dev/null || pwd)"
# 宿主机 workspace 目录：bind mount 到容器 /workspace，方便直接在服务器查看 task 产物。
# 默认 = 项目目录/agent-workspace；可用 WORKSPACE_HOST_DIR 覆盖。
WORKSPACE_HOST_DIR="${WORKSPACE_HOST_DIR:-$HOST_DIR/agent-workspace}"
mkdir -p "$WORKSPACE_HOST_DIR"
# 宿主机技能目录（相对仓库根），只读挂到容器 /skills。目录不存在时先建好，
# 否则 bind mount 会按 root 属主自动创建，容易给后续写入埋坑。
SKILLS_HOST_DIR="${SKILLS_HOST_DIR:-agent-skills}"
[ -d "$SKILLS_HOST_DIR" ] || mkdir -p "$SKILLS_HOST_DIR"
SKILLS_SRC="$HOST_DIR/$SKILLS_HOST_DIR"

if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: $ENV_FILE not found. Copy .env.example to $ENV_FILE and fill it in." >&2
  exit 1
fi


# ---------- agent CLI（cli-tools/ips）所需的环境变量 ----------
# ips 二进制读 IPS_BASE_URL / IPS_CACHE_DIR 这两个 OS 环境变量（不是 Spring 配置），而 agent 的 shell
# 就在本容器内执行，所以必须显式注入容器环境，否则命令直接报「未配置 IPS_BASE_URL」。
# 取值优先级：shell 环境变量 > $ENV_FILE 里的同名键 > 脚本默认。所以这几个 IPS_* 直接写在 .env 里
# 就行（--env-file 本来也会把它们带进容器）；脚本这里再解析一遍，是为了 a) 结尾能打印真正生效的值，
# b) 允许用 AGENT_IPS_* 一个旋钮同时管 Java 侧和 CLI 侧。
env_from_file() { # $1=key -> .env 里的值（去掉 CRLF），没有则空
  awk -F= -v k="$1" '$1==k {gsub(/\r/,""); sub(/^[^=]*=/,""); print}' "$ENV_FILE" | tail -1
}
pick() { # $1=key $2=default -> shell > .env > default
  local v="${!1:-}"
  [ -n "$v" ] || v="$(env_from_file "$1")"
  [ -n "$v" ] || v="$2"
  printf '%s' "$v"
}
# base-url 与 Java 侧同源以免两处漂移：没显式给 IPS_BASE_URL 时退回 AGENT_IPS_BASE_URL。
IPS_URL="$(pick IPS_BASE_URL "")"
if [ -z "$IPS_URL" ]; then
  IPS_URL="$(env_from_file AGENT_IPS_BASE_URL)"
fi
# 缓存放 /workspace（已 bind mount 到宿主机）而不是默认的用户家目录：换容器不丢，跨任务同一份文件
# 只请求 IPS 一次；它在任务目录之外，不受 delete-workspace-on-finish 影响。
IPS_CACHE="$(pick IPS_CACHE_DIR /workspace/.ips-cache)"
# 超时：ips CLI 1.0.5 起自带 --timeout 默认 180s，够用，这里不再预设。只有想把它和 Java 侧的
# AGENT_IPS_TIMEOUT 绑成一个旋钮时才注入（cli-tools/ips 见到 IPS_TIMEOUT_SECONDS 才追加 --timeout）。
IPS_TMO="$(pick IPS_TIMEOUT_SECONDS "")"
if [ -z "$IPS_TMO" ]; then
  IPS_TMO="$(env_from_file AGENT_IPS_TIMEOUT)"
fi
CLI_ENV=(-e "IPS_CACHE_DIR=$IPS_CACHE")
if [ -n "$IPS_TMO" ]; then
  CLI_ENV+=(-e "IPS_TIMEOUT_SECONDS=$IPS_TMO")
fi
if [ -n "$IPS_URL" ]; then
  CLI_ENV+=(-e "IPS_BASE_URL=$IPS_URL")
else
  echo "WARN: $ENV_FILE 里没有 AGENT_IPS_BASE_URL，容器内 ips 命令会因缺 IPS_BASE_URL 直接失败。" >&2
fi
# 子命令白名单：只有显式给了才注入，否则留给包装脚本用自己的默认（idcard,bizlic,invoice,seal,card,handprint）。
IPS_SUBS="$(pick IPS_ALLOWED_SUBCOMMANDS "")"
if [ -n "$IPS_SUBS" ]; then
  CLI_ENV+=(-e "IPS_ALLOWED_SUBCOMMANDS=$IPS_SUBS")
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
  "${CLI_ENV[@]}" \
  -e AGENT_WORKING_DIR=/workspace \
  -e AGENT_SKILLS_DIR=/skills \
  -p "${PORT}:8080" \
  -v "${WORKSPACE_HOST_DIR}:/workspace" \
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

# CLI 是否真的可用：镜像里有没有二进制、PATH 通不通、版本对不对，一条命令就能确认。
ips_ver="$(docker exec "$NAME" sh -c 'command -v ips >/dev/null && ips --version' 2>/dev/null || echo 未安装)"
echo "==> agent CLI: $ips_ver（IPS_BASE_URL=${IPS_URL:-未设置}, IPS_CACHE_DIR=$IPS_CACHE, IPS_TIMEOUT_SECONDS=${IPS_TMO:-用 CLI 默认 180}, IPS_ALLOWED_SUBCOMMANDS=${IPS_SUBS:-包装脚本默认}）"

echo "==> 日志:   docker logs -f $NAME"
echo "==> 停止:   docker stop $NAME        （SIGTERM 优雅停机，正在跑的任务会被租约回收重跑）"
echo "==> 进入:   docker exec -it $NAME bash   # 镜像内有 python/curl/jq/rg，可直接排查"
