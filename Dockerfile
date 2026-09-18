# 注意：这里刻意不写 `# syntax=docker/dockerfile:1`。那一行会让 BuildKit 去
# docker.io 拉 dockerfile frontend，而本机构建环境访问不了 auth.docker.io（实测超时），
# 用内置 frontend 即可——本文件用到的 ARG/COPY/HEALTHCHECK/ENTRYPOINT 均属内置支持。
#
# ai-agent-java 运行镜像：JDK 17 + 完整 Python 3 环境 + agent 常用 shell 工具集。
#
# 构建（推荐用脚本，会自动带版本号）：
#   bash scripts/docker-build.sh                # 本机编译 jar + 打镜像（tag 取 pom <version>）
#   SKIP_BUILD=1 bash scripts/docker-build.sh   # 复用已有 target/*.jar
#
# 为什么镜像里要装这么多命令：agent 的 shell 工具**就在本容器内执行**。缺 curl/grep/jq/python3
# 不是"少个功能"，而是依赖它的任务直接失败——模型无法绕过不存在的二进制，只会反复试错、
# 白烧任务时间预算。所以按 agent 实际会用的命令一次性备齐。
#
# 为什么不在容器里跑 Maven 构建：本项目依赖走内网 Nexus，settings.xml 里声明的是 Windows
# 路径的 localRepository，只有宿主机原生 Maven 能正确解析（早前容器内构建出现过 classpath 为空）。
# 顺带的好处：带凭据的 settings.xml 永远不会进入任何镜像层或构建上下文。

ARG BASE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:17-jre-noble
FROM ${BASE_IMAGE}

# FROM 之前声明的 ARG 属于全局作用域，stage 内的指令看不到；这里重新声明同名 ARG，
# 值仍取 --build-arg 传入的那个，以便把它写进下面的 OCI label。
ARG BASE_IMAGE

# ---------- 构建期参数 ----------
# 版本三元组由 scripts/docker-build.sh 注入，落在 OCI label 里，便于线上反查构建来源。
ARG APP_VERSION=0.0.0-unknown
ARG GIT_COMMIT=unknown
ARG BUILD_TIME=unknown
ARG JAR_FILE=target/ai-agent-java-1.0.0.jar
ARG APT_MIRROR=mirrors.aliyun.com
ARG APP_UID=10001
ARG APP_GID=10001

# 版本元信息 LABEL 故意放到文件最末尾（见下）。若放在 apt 那层 RUN 之前，BUILD_TIME
# 每次构建都是新时间戳，会让 LABEL 的缓存 key 每次都变，连带它之后的 apt 层每次重装
# （实测每多花 ~4 分钟）。放在最后，改代码/改版本号都不再影响 apt 缓存。

# ---------- 语言与时区 ----------
# LANG/LC_ALL 必须是 UTF-8：否则 shell 命令输出的中文在日志和接口返回里都是乱码（本机
# 控制台 GBK 的老问题在容器里的对应项）。C.UTF-8 是 Ubuntu 内置 locale，无需 locale-gen。
# TZ 影响的不只是 date 输出：模型判断"今天几号"依赖进程时区。
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TZ=Asia/Shanghai

# ---------- Python 行为 ----------
# PYTHONUNBUFFERED：agent 常用 `python x.py | grep` 之类的管道，缓冲会让输出丢失或迟到。
# PIP_BREAK_SYSTEM_PACKAGES：Ubuntu 24.04 有 PEP 668 保护，不设这个则容器内
#   `pip install requests` 会直接报错拒绝，agent 每次都得自己发明 --break-system-packages。
# PIP_INDEX_URL：默认走国内镜像；部署环境若无公网，用 --build-arg/环境变量覆盖或留空走官方源。
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_BREAK_SYSTEM_PACKAGES=1 \
    PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple

# ---------- 系统依赖与工具集 ----------
# 用 --no-install-recommends：python3-pandas 的 Recommends 会拖进 matplotlib 等数百 MB，
# 而 agent 处理表格只需要 pandas 本身。
RUN set -eux; \
    # Ubuntu 24.04 用 deb822 格式的源文件（不再是 /etc/apt/sources.list）。
    # 纯内网构建时把 APT_MIRROR 换成内网 apt 镜像地址即可。
    if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then \
        sed -i \
          -e "s|http://archive.ubuntu.com/ubuntu/|https://${APT_MIRROR}/ubuntu/|g" \
          -e "s|http://security.ubuntu.com/ubuntu/|https://${APT_MIRROR}/ubuntu/|g" \
          /etc/apt/sources.list.d/ubuntu.sources; \
    fi; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        # 基础：证书（curl/https 与 JDBC TLS）、时区数据
        ca-certificates tzdata \
        # 网络与诊断：agent 抓远程文件、排查连通性问题
        curl wget iputils-ping iputils-tracepath dnsutils \
        # 进程与磁盘（排查用，也给 agent 提供 ps/df）
        procps psmisc util-linux \
        # 文本处理：agent 最高频的一组命令
        grep sed gawk mawk jq ripgrep fd-find findutils diffutils patch dos2unix \
        # 文件与归档
        tar gzip bzip2 xz-utils zstd zip unzip file tree rsync \
        # 计算与编辑
        bc moreutils less nano vim-tiny \
        # 版本控制与传输（agent 会 clone 仓库、拉配置）
        git openssh-client openssl \
        # Python 3 完整环境：解释器 + pip + venv + 头文件（能现场编译 C 扩展）
        python3 python3-pip python3-venv python3-dev python3-setuptools \
        python-is-python3 \
        # 常用 Python 库走 apt 预编译包：构建期不依赖 PyPI，比 pip 装更快更稳
        python3-numpy python3-pandas python3-requests python3-yaml \
        python3-dateutil python3-tabulate python3-openpyxl \
        python3-lxml python3-bs4 \
    ; \
    ln -sf "/usr/share/zoneinfo/${TZ}" /etc/localtime; \
    dpkg-reconfigure -f noninteractive tzdata >/dev/null 2>&1 || true; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/*

# ---------- 用户与工作目录 ----------
# 非 root 运行。真正的安全边界是容器 + 每任务独立子目录（文件工具被框架限制在
# <working-dir>/<taskId> 内），不是 OS 用户；但非 root 仍能挡住"容器内横向写系统目录"。
# /workspace  任务工作区（可写，部署时挂 named volume），每任务一个子目录
# /skills     技能目录（只读，部署时 bind-mount 宿主机 agent-skills）
#             故意与 workspace 分开：任务产物是可写的、技能是部署配置只读；
#             分开后运维在宿主机直接改 SKILL.md 就能热生效，不须 docker cp 进 volume。
RUN groupadd --gid "${APP_GID}" appgroup \
    && useradd --create-home --uid "${APP_UID}" --gid "${APP_GID}" appuser \
    && mkdir -p /workspace /skills \
    && chown appuser:appgroup /workspace

# jar 属 root:root、权限 644：appuser 能读、能执行，但改不了自己跑的 jar。
COPY ${JAR_FILE} /app/app.jar

# /workspace 是 agent 的工作根目录（AGENT_WORKING_DIR 默认指向这里）。
# 每个任务实际运行在 /workspace/<taskId>，shell 与文件工具的 cwd 就是它。
WORKDIR /workspace

# JAVA_OPTS 可整体覆盖（docker run -e JAVA_OPTS=...）。不在此处预设 SPRING_PROFILES_ACTIVE
# 等应用变量：那些属于部署配置，由 .env / compose 注入，镜像预设反而容易遮住外部传值。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

# start-period 给足 60 秒：启动要连 MySQL、建表/校验、拉起 worker 线程；
# 在这期间探活失败不算故障，避免刚起来就被编排系统杀掉。
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/api/v1/agent/health || exit 1

USER appuser

# 用 sh -c + exec：让 JAVA_OPTS 生效（纯 exec form 不做变量展开），同时 exec 保证
# java 进程仍是 PID 1，能收到 docker stop 的 SIGTERM 走优雅停机。
# "$@" 透传 CMD 参数，便于临时追加 --agent.xxx=yyy 这类 Spring Boot 覆盖项。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]

# 版本元信息放在最末尾：这些 ARG 每次构建都变，放这里只会使当前这个轻量层失效，
# 不会波及前面昂贵的 apt / COPY 层。
LABEL org.opencontainers.image.title="ai-agent-java" \
      org.opencontainers.image.description="Natural-language agent HTTP service (Spring Boot 4 + AgentScope Java 2.0.3)" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.created="${BUILD_TIME}" \
      org.opencontainers.image.base.name="${BASE_IMAGE}"
