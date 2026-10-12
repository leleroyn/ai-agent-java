# 注意：这里刻意不写 `# syntax=docker/dockerfile:1`。那一行会让 BuildKit 去
# docker.io 拉 dockerfile frontend，而本机构建环境访问不了 auth.docker.io（实测超时），
# 用内置 frontend 即可——本文件用到的 ARG/COPY/HEALTHCHECK/ENTRYPOINT 均属内置支持。
#
# ai-agent-java 应用镜像：放 jar + 仓库自带的 CLI 二进制。运行环境（JDK17 + Python3 + shell 工具集 + 运行用户）
# 来自基础镜像 ai-agent-base（见 Dockerfile.base）。因此本构建不跑 apt、只叠一层 jar，秒级完成。
#
# 构建（推荐用脚本，会自动带版本号；本机 Maven 编译 jar）：
#   bash scripts/docker-build.sh                # 需先构建过基础镜像，脚本会自检并提示
#   SKIP_BUILD=1 bash scripts/docker-build.sh   # 复用已有 ai-agent-app/target/*.jar
#
# 先构建基础镜像（仅首次、或增删系统工具/Python 库时）：
#   bash scripts/docker-build-base.sh
#
# 为什么不在容器里跑 Maven 构建：本项目依赖走内网 Nexus，settings.xml 里声明的是 Windows
# 路径的 localRepository，只有宿主机原生 Maven 能正确解析（早前容器内构建出现过 classpath 为空）。
# 顺带的好处：带凭据的 settings.xml 永远不会进入任何镜像层或构建上下文。

# 基础镜像由 scripts/docker-build.sh 注入（默认 ai-agent-base:<BASE_VERSION>）。
# 若换 registry，用 --build-arg BASE_IMAGE=... 覆盖。
ARG BASE_IMAGE=ai-agent-base:1.0
FROM ${BASE_IMAGE}

# FROM 之前声明的 ARG 属于全局作用域，stage 内的指令看不到；重新声明同名 ARG 以便写进 label。
ARG BASE_IMAGE

# ---------- 构建期参数 ----------
# 版本三元组由 scripts/docker-build.sh 注入，落在 OCI label 里，便于线上反查构建来源。
ARG APP_VERSION=0.0.0-unknown
ARG GIT_COMMIT=unknown
ARG BUILD_TIME=unknown
ARG JAR_FILE=ai-agent-app/target/ai-agent-java-1.2.0.jar

# jar 属 root:root、权限 644：appuser 能读、能执行，但改不了自己跑的 jar。
COPY ${JAR_FILE} /app/app.jar

# ---------- agent 可调用的 CLI 工具（cli-tools/） ----------
# 随应用一起发版的静态二进制（Rust/musl static-pie，不依赖 glibc；实测在 eclipse-temurin:17-jre-noble
# 上直接跑）+ 一层 sh 包装脚本。agent 的 shell 工具就在本容器内执行，包装脚本进 PATH 就能被
# `ips <子命令> <文件>` 调起；真二进制在 native/ 下、不在 PATH 上，由包装脚本按 IPS_ALLOWED_SUBCOMMANDS
# 决定放行哪些子命令（框架的 shell 白名单只比对命令的第一个 token，判不到子命令，只能在这一层判）。
#
# 为什么放应用镜像而不是基础镜像：这些二进制跟仓库一起演进、换版本是常事，放应用层只重建这一层（秒级）；
# 基础镜像重建要跑 apt（外网 + 分钟级），不该被 CLI 版本变更触发。
COPY cli-tools/ /opt/agent-cli/
# Windows 宿主机上 COPY 进来的可执行位不可靠，显式补权限；紧接着跑一次 --version 当构建期冒烟测试——
# 架构/ABI 不匹配（例如在 arm64 机器上构建 x86 二进制）当场构建失败，而不是等线上 agent 调它才发现。
RUN chmod -R a+rX /opt/agent-cli \
    && /opt/agent-cli/ips --version >/dev/null \
    && /opt/agent-cli/ips seal --help >/dev/null
# 白名单冒烟：默认配置下 `ips ocr` 必须被包装脚本拒掉（退出码 3）。构建期就挡住「包装层没生效」
# 这种事故，否则线上会变成模型能在 shell 里 OCR 整篇 72 页原文、把主上下文灌爆。
RUN /opt/agent-cli/ips ocr probe-nonexistent >/dev/null 2>&1; [ "$?" -eq 3 ]
# 全局选项冒烟：ips --timeout 5 seal <不存在文件> 必须走到真二进制（退出码 1），不能被白名单误拒（3）。
# clap 的全局选项可以写在任意位置，包装器定位子命令时必须跳过它们，否则模型这样写会被误判成「没开放」。
RUN /opt/agent-cli/ips --timeout 5 seal probe-nonexistent >/dev/null 2>&1; [ "$?" -eq 1 ]

# /opt/agent-cli 进 PATH：agent 写 `ips bizlic src/x.jpg` 即可，不必记绝对路径。
ENV PATH="/opt/agent-cli:${PATH}"

# /workspace 是 agent 的工作根目录（AGENT_WORKING_DIR 默认指向这里），基础镜像已建好并设为 WORKDIR。
WORKDIR /workspace

# JAVA_OPTS 可整体覆盖（docker run -e JAVA_OPTS=...）。不在此处预设 SPRING_PROFILES_ACTIVE
# 等应用变量：那些属于部署配置，由 .env / compose 注入，镜像预设反而容易遮住外部传值。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

# start-period 给足 60 秒：启动要连 MySQL、建表/校验、拉起 worker 线程；
# 在这期间探活失败不算故障，避免刚起来就被编排系统杀掉。curl 由基础镜像提供。
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/api/v1/agent/health || exit 1

USER appuser

# 用 sh -c + exec：让 JAVA_OPTS 生效（纯 exec form 不做变量展开），同时 exec 保证
# java 进程仍是 PID 1，能收到 docker stop 的 SIGTERM 走优雅停机。
# "$@" 透传 CMD 参数，便于临时追加 --agent.xxx=yyy 这类 Spring Boot 覆盖项。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]

# 版本元信息放在最末尾：这些 ARG 每次构建都变，放这里只使当前这层（jar 之上的极轻 metadata）失效，
# 与前面 COPY jar 层解耦，便于同一 jar 复用。
LABEL org.opencontainers.image.title="ai-agent-java" \
      org.opencontainers.image.description="Natural-language agent HTTP service (Spring Boot 4 + AgentScope Java 2.0.3)" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.created="${BUILD_TIME}" \
      org.opencontainers.image.base.name="${BASE_IMAGE}"
