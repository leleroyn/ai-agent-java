#!/usr/bin/env bash
# 打版本镜像：本机 Maven 编译 fat jar -> docker build -> 打版本号 / latest / commit 三个 tag。
#
#   bash scripts/docker-build.sh                    # 完整流程：编译 + 构建镜像
#   SKIP_BUILD=1 bash scripts/docker-build.sh       # 复用已有 target/*.jar
#   IMAGE_REPO=myreg.local:5000/ai-agent-java bash scripts/docker-build.sh
#   PUSH=1 IMAGE_REPO=myreg.local:5000/ai-agent-java bash scripts/docker-build.sh
#   NO_CACHE=1 bash scripts/docker-build.sh         # apt 层也重建
#
# 版本号取自 pom.xml 的 <version>（跳过 <parent> 块）。git commit 取不到时记 unknown，
# 不阻断构建——本项目当前尚未初始化 git，这是预期情况。
set -euo pipefail
cd "$(dirname "$0")/.."

IMAGE_REPO="${IMAGE_REPO:-ai-agent-java}"
PUSH="${PUSH:-0}"
NO_CACHE="${NO_CACHE:-0}"
SKIP_BUILD="${SKIP_BUILD:-0}"
APT_MIRROR="${APT_MIRROR:-mirrors.aliyun.com}"
BASE_IMAGE="${BASE_IMAGE:-docker.m.daocloud.io/library/eclipse-temurin:17-jre-noble}"

# pom <version>：跳过 <parent> 块，取项目自身版本。
VERSION="$(awk '/<parent>/{p=1} /<\/parent>/{p=0}
  !p && /<version>/{gsub(/.*<version>|<\/version>.*/,""); print; exit}' pom.xml)"
if [ -z "$VERSION" ]; then
  echo "ERROR: 无法从 pom.xml 解析 <version>" >&2
  exit 1
fi

GIT_COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
# 工作区是否干净：脏工作区打出的镜像与 commit 并不等价，必须标出来，否则线上排障会被误导。
if [ "$GIT_COMMIT" != "unknown" ] && [ -n "$(git status --porcelain 2>/dev/null)" ]; then
  GIT_COMMIT="${GIT_COMMIT}-dirty"
fi
BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

JAR="target/ai-agent-java-${VERSION}.jar"

echo "==> version    : $VERSION"
echo "==> git commit : $GIT_COMMIT"
echo "==> build time : $BUILD_TIME"
echo "==> base image : $BASE_IMAGE"
echo "==> apt mirror : $APT_MIRROR"

if [ "$SKIP_BUILD" != "1" ]; then
  echo "==> 编译 jar（本机 Maven + 内网 Nexus）"
  if ! bash scripts/build.sh; then
    # 实测踩过：本地服务（scripts/run-local.sh）正用 java -jar 跑着 target/*.jar，
    # Windows 会锁住该文件，maven-clean 删不掉 → BUILD FAILURE，报错信息是乱码的
    # “另一个程序正在使用此文件”，很容易被误认为代码问题。
    echo "ERROR: 编译失败。若日志里是 clean 阶段删不掉 target/*.jar，" >&2
    echo "       先停掉本地服务（它正用 java -jar 锁着该文件），或改用 GOAL=package 跳过 clean。" >&2
    exit 1
  fi
else
  echo "==> SKIP_BUILD=1，复用已有 jar"
fi

if [ ! -f "$JAR" ]; then
  echo "ERROR: 找不到 $JAR；实际产物：" >&2
  ls -la target/*.jar 2>/dev/null >&2 || echo "  target/ 下没有任何 jar" >&2
  echo "提示：jar 名与 pom <version> 不一致时，显式指定 JAR_FILE=... 或先去掉 SKIP_BUILD" >&2
  exit 1
fi

# 只放行 jar 进上下文（.dockerignore 已排除 .env 等敏感文件），构建上下文应远小于仓库体积。
echo "==> 构建上下文（排除 .env/源码/文档后）："
if command -v du >/dev/null 2>&1; then
  du -sh --exclude=.git --exclude=agent-workspace . 2>/dev/null | awk '{print "    " $1}'
fi

BUILD_ARGS=(
  --build-arg "APP_VERSION=${VERSION}"
  --build-arg "GIT_COMMIT=${GIT_COMMIT}"
  --build-arg "BUILD_TIME=${BUILD_TIME}"
  --build-arg "JAR_FILE=${JAR}"
  --build-arg "APT_MIRROR=${APT_MIRROR}"
  --build-arg "BASE_IMAGE=${BASE_IMAGE}"
  -t "${IMAGE_REPO}:${VERSION}"
  -t "${IMAGE_REPO}:latest"
)
# commit tag 便于按代码版本回滚；dirty 版本不打 commit tag，避免误认为可复现。
if [ "$GIT_COMMIT" != "unknown" ] && [[ "$GIT_COMMIT" != *-dirty ]]; then
  BUILD_ARGS+=(-t "${IMAGE_REPO}:${VERSION}-${GIT_COMMIT}")
fi
[ "$NO_CACHE" = "1" ] && BUILD_ARGS+=(--no-cache)

echo "==> docker build ${IMAGE_REPO}:${VERSION}"
docker build "${BUILD_ARGS[@]}" .

echo
echo "==> 镜像："
docker images --format '  {{.Repository}}:{{.Tag}}  {{.Size}}  created={{.CreatedSince}}' \
  --filter "reference=${IMAGE_REPO}" | head -5

echo
echo "==> 镜像内环境自检（基础镜像是否真的带齐 agent 要用的命令）："
docker run --rm "${IMAGE_REPO}:${VERSION}" --version >/dev/null 2>&1 || true
docker run --rm --entrypoint sh "${IMAGE_REPO}:${VERSION}" -eu -c '
  echo "  os      : $(. /etc/os-release; echo "$PRETTY_NAME")"
  echo "  java    : $(java -version 2>&1 | head -1)"
  echo "  python  : $(python --version 2>&1)  -> $(command -v python)"
  echo "  pip     : $(pip --version 2>&1 | cut -d" " -f1-2)"
  echo "  user    : $(id -un) uid=$(id -u) home=$HOME cwd=$(pwd)"
  echo "  locale  : LANG=$LANG TZ=$TZ  date=$(date "+%Y-%m-%d %H:%M:%S %Z")"
  echo "  命令检查："
  for c in curl wget jq rg grep sed gawk awk python3 pip git tar unzip zip xz zstd file tree rsync \
           diff patch bc sort uniq head tail wc find xargs ps df du less nano openssl dig ping; do
    command -v "$c" >/dev/null 2>&1 || echo "    缺失: $c"
  done
  echo "    （上面若无“缺失”行即全部就位）"
  python - <<PY
import pandas, numpy, requests, yaml, dateutil, openpyxl, lxml, bs4
print("  python 库: pandas=%s numpy=%s requests=%s openpyxl=%s" % (
    pandas.__version__, numpy.__version__, requests.__version__, openpyxl.__version__))
PY
'

if [ "$PUSH" = "1" ]; then
  echo "==> 推送"
  docker push "${IMAGE_REPO}:${VERSION}"
  docker push "${IMAGE_REPO}:latest"
else
  echo "==> 未推送（PUSH=1 可推送到 ${IMAGE_REPO}）"
fi

echo
echo "下一步： bash scripts/docker-run.sh            # 用刚打好的镜像启动（默认复用，--build 才重打）"
echo "        或指定版本： IMAGE=${IMAGE_REPO}:${VERSION} bash scripts/docker-run.sh"
