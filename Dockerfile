# 注意：这里刻意不写 `# syntax=docker/dockerfile:1`。那一行会让 BuildKit 去
# docker.io 拉 dockerfile frontend，而本机构建环境访问不了 auth.docker.io（实测超时），
# 用内置 frontend 即可——本文件用到的 ARG/COPY/HEALTHCHECK/ENTRYPOINT 均属内置支持。
#
# ai-agent-java 应用镜像：只负责放 jar。运行环境（JDK17 + Python3 + shell 工具集 + 运行用户）
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
