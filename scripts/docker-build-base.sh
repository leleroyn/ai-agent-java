#!/usr/bin/env bash
# 构建「环境基础镜像」ai-agent-base：JDK17 + Python3 + agent shell 工具集 + 运行用户。
# 这一层几乎不变，只有增删系统工具/Python 库时才重建；日常改 Java 代码用 docker-build.sh（FROM 本镜像）。
#
#   bash scripts/docker-build-base.sh                       # 构建并打 ai-agent-base:${BASE_VERSION} + :latest
#   SAVE=1 bash scripts/docker-build-base.sh                # 另存离线包 dist/ai-agent-base-<ver>.tar.gz
#   PUSH=1 IMAGE_REPO=myreg.local:5000/ai-agent-base bash scripts/docker-build-base.sh   # 推送
#   BASE_VERSION=1.1 bash scripts/docker-build-base.sh      # 增删工具时手动抬版本，让依赖方重新拉
#
# BASE_VERSION 与 app 版本解耦：它只在基础镜像内容（apt 工具/Python 库）变化时才需要 +1。
# 依赖方 scripts/docker-build.sh 默认 FROM ai-agent-base:${BASE_VERSION}，两边 BASE_VERSION 要一致。
set -euo pipefail
cd "$(dirname "$0")/.."

IMAGE_REPO="${IMAGE_REPO:-ai-agent-base}"
BASE_VERSION="${BASE_VERSION:-1.0}"
PUSH="${PUSH:-0}"
SAVE="${SAVE:-0}"
NO_CACHE="${NO_CACHE:-0}"
APT_MIRROR="${APT_MIRROR:-mirrors.aliyun.com}"
# 上游基础镜像（eclipse-temurin）。内网可用 --build-arg/环境变量换成内网镜像地址。
UPSTREAM_BASE="${UPSTREAM_BASE:-docker.m.daocloud.io/library/eclipse-temurin:17-jre-noble}"
BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

echo "==> base image repo : ${IMAGE_REPO}"
echo "==> base version    : ${BASE_VERSION}"
echo "==> upstream base   : ${UPSTREAM_BASE}"
echo "==> apt mirror      : ${APT_MIRROR}"

BUILD_ARGS=(
  -f Dockerfile.base
  --build-arg "BASE_IMAGE=${UPSTREAM_BASE}"
  --build-arg "BASE_VERSION=${BASE_VERSION}"
  --build-arg "APT_MIRROR=${APT_MIRROR}"
  -t "${IMAGE_REPO}:${BASE_VERSION}"
  -t "${IMAGE_REPO}:latest"
)
[ "$NO_CACHE" = "1" ] && BUILD_ARGS+=(--no-cache)

echo "==> docker build ${IMAGE_REPO}:${BASE_VERSION}（首次含 apt，约数分钟；有缓存则很快）"
docker build "${BUILD_ARGS[@]}" .

echo
echo "==> 基础镜像自检（agent 要用的命令/库是否齐）："
docker run --rm --entrypoint sh "${IMAGE_REPO}:${BASE_VERSION}" -eu -c '
  echo "  os     : $(. /etc/os-release; echo "$PRETTY_NAME")"
  echo "  java   : $(java -version 2>&1 | head -1)"
  echo "  python : $(python --version 2>&1) -> $(command -v python)"
  echo "  pip    : $(pip --version 2>&1 | cut -d" " -f1-2)"
  echo "  pdf    : pdftoppm=$(command -v pdftoppm) pdfinfo=$(command -v pdfinfo)"
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

if [ "$SAVE" = "1" ]; then
  mkdir -p dist
  OUT="dist/${IMAGE_REPO}-${BASE_VERSION}.tar.gz"
  echo "==> 导出离线包 ${OUT}"
  docker save "${IMAGE_REPO}:${BASE_VERSION}" | gzip -1 > "${OUT}"
  sha256sum "${OUT}" 2>/dev/null | cut -c1-16 | sed 's/^/    sha256(前16)=/'
  ls -la "${OUT}"
fi

if [ "$PUSH" = "1" ]; then
  echo "==> 推送"
  docker push "${IMAGE_REPO}:${BASE_VERSION}"
  docker push "${IMAGE_REPO}:latest"
else
  echo "==> 未推送（PUSH=1 可推送到 ${IMAGE_REPO}）"
fi

echo
echo "下一步： bash scripts/docker-build.sh        # FROM 本基础镜像打应用镜像（秒级，不跑 apt）"
