# 构建与部署

镜像 = JDK 17 运行时 + 完整 Python 3 环境 + agent 常用 shell 工具集。agent 的 shell 工具**就在本容器内执行**，所以镜像里缺什么命令，依赖它的任务就直接失败——工具集是按这个标准备齐的，不是随手装的。

## 1. 构建（基础镜像 + 应用镜像，两段式）

镜像拆成两层，为了“每次构建都快”：

- **环境基础镜像** `ai-agent-base`（JDK + Python + apt 工具集 + 运行用户）：几乎不变，**只在首次或增删系统工具/Python 库时才重建**，含 apt、较慢。
- **应用镜像** `ai-agent-java`：`FROM ai-agent-base` + `COPY jar`，**不跑 apt、秒级**。日常改 Java 代码只打这个。

```bash
# 环境基础镜像：首次或增删工具时才跑（含 apt，约数分钟）
bash scripts/docker-build-base.sh                 # 产出 ai-agent-base:1.0 + :latest
SAVE=1 bash scripts/docker-build-base.sh          # 另存离线包 dist/ai-agent-base-1.0.tar.gz

# 应用镜像：日常改代码只跑这个（FROM 基础镜像 + COPY jar，秒级；基础镜像缺失会提示先建）
bash scripts/docker-build.sh                      # 本机编译 jar + 打应用镜像
SKIP_BUILD=1 bash scripts/docker-build.sh         # 复用已有 target/*.jar
PUSH=1 IMAGE_REPO=reg.local:5000/ai-agent-java bash scripts/docker-build.sh
```

> 基础镜像版本由 `BASE_VERSION`（默认 `1.0`）控制，与 app 版本解耦：只有基础镜像内容（apt 工具/Python 库）变化才需要抬 `BASE_VERSION` 并重建；`docker-build.sh` 默认 `FROM ai-agent-base:${BASE_VERSION}`，两边要对齐。

版本号取自 `pom.xml` 的 `<version>`，应用镜像产出三个 tag：

| tag | 用途 |
|---|---|
| `ai-agent-java:1.2.0` | **按版本部署/回滚用这个** |
| `ai-agent-java:latest` | 本地开发便利，生产别用 |
| `ai-agent-java:1.2.0-<git短sha>` | 按代码版本回溯；工作区脏时改打 `-dirty` 且不出此 tag |

版本三元组（version / commit / build time）与基础镜像写入 OCI label，事后可直接反查：

```bash
docker image inspect ai-agent-java:1.2.0 \
  --format '{{index .Config.Labels "org.opencontainers.image.version"}} {{index .Config.Labels "org.opencontainers.image.revision"}} {{index .Config.Labels "org.opencontainers.image.created"}}'
```

> 版本由 `pom.xml` 的 `<version>` 单一决定（当前 `1.2.0`），`docker-build.sh` 会解析它作为镜像 tag 与 jar 名。发新版只改这一处即可，不要再带 `-SNAPSHOT`（语义上是"未定稿"，也不利于按版本回滚）。

## 2. 镜像里有什么

实测自检输出（`docker-build.sh` 每次构建结束会自动跑这段）：

```
os      : Ubuntu 24.04.5 LTS
java    : openjdk version "17.0.20" 2026-07-21
python  : Python 3.12.3  -> /usr/bin/python
pip     : pip 24.0
user    : appuser uid=10001 home=/home/appuser cwd=/workspace
locale  : LANG=C.UTF-8 TZ=Asia/Shanghai  date=2026-09-18 09:41:23 CST
python 库: pandas=2.1.4 numpy=1.26.4 requests=2.31.0 openpyxl=3.1.2
命令检查：（无缺失行）
```

| 分组 | 内容 |
|---|---|
| 运行时 | Eclipse Temurin JDK 17.0.20（基础镜像即 `eclipse-temurin:17-jre-noble`） |
| Python | 3.12 解释器 + `python`/`python3` 双命令 + pip 24 + venv + dev 头文件（可现场编译 C 扩展） |
| Python 库 | numpy、pandas、requests、PyYAML、dateutil、tabulate、openpyxl、lxml、bs4 |
| 网络 | curl、wget、ping、traceroute、dig/nslookup（dnsutils） |
| 文本 | grep、sed、gawk、mawk、jq、ripgrep(rg)、fd、find、diff、patch、dos2unix、sort/uniq/head/tail/wc |
| 文件归档 | tar、gzip、bzip2、xz、zstd、zip、unzip、file、tree、rsync |
| 其他 | git、openssh-client、openssl、bc、moreutils、less、nano、vim-tiny、ps/df/du（procps/psmisc/util-linux） |
| 本地化 | `LANG=LC_ALL=C.UTF-8`、`TZ=Asia/Shanghai`（否则中文输出乱码、模型判断"今天几号"会错） |

要点：

- **Python 库走 apt 预编译包**，构建期完全不依赖 PyPI，比 pip 装更快更稳（这台构建环境也访问不了 pypi.org）。运行时想临时装包仍可 `pip install`——已预置 `PIP_BREAK_SYSTEM_PACKAGES=1`，绕开 Ubuntu 24.04 的 PEP 668 拦截；`PIP_INDEX_URL` 默认清华源，无公网时用环境变量覆盖或置空。
- `--no-install-recommends` 是刻意的：否则 `python3-pandas` 会拖进 matplotlib 等数百 MB。
- jar 属 `root:root` 644、进程以 `appuser`(uid 10001) 运行：能读能跑，改不了自己跑的 jar。
- apt 只在基础镜像里跑：`docker-build-base.sh` 默认 apt 源 `mirrors.aliyun.com`、上游基础镜像 `eclipse-temurin:17-jre-noble`（走 daocloud 加速）。纯内网构建传 `APT_MIRROR=<内网源>`、`UPSTREAM_BASE=<内网 eclipse-temurin:17-jre-noble>`。`docker-build.sh` 的 `BASE_IMAGE` 现在指这个环境基础镜像（默认 `ai-agent-base:1.0`），换 registry 时改它。

## 3. 离线分发（目标机连不上 registry）

构建机导出：

> **基础镜像拆分只影响构建期，不影响分发**：`docker save` 应用镜像会把**全部基础层（环境）+ jar 层**一起打包，所以目标机只需加载应用镜像包即可，**无需单独加载 `ai-agent-base`**。（若想复用环境层、单独分发基础镜像以省传输，可选做；不是必需。）

```bash
docker save ai-agent-java:1.2.0 ai-agent-java:latest | gzip -6 > dist/ai-agent-java-1.2.0.tar.gz
( cd dist && sha256sum ai-agent-java-1.2.0.tar.gz > ai-agent-java-1.2.0.tar.gz.sha256 )
```

实测：1.06GB 镜像 → **264MB / 19 秒**。校验和必须在 `dist/` 目录内生成（文件里只记纯文件名），否则对端 `sha256sum -c` 会因为路径前缀对不上而失败。

目标机导入：

```bash
( cd dist && sha256sum -c ai-agent-java-1.2.0.tar.gz.sha256 )   # 期望输出 ...: OK
docker load -i dist/ai-agent-java-1.2.0.tar.gz                 # 自动识别 gzip，实测 36 秒
bash scripts/docker-run.sh                                     # 之后与常规启动一致
```

已实测：删掉本地 `ai-agent-java` 镜像（模拟全新机器）后从 tar 重新 `load`，版本 tag 与 `latest` 全部恢复，镜像 ID 与导出前完全一致（环境层已含在内，能直接跑）。

`dist/` 已参加 `.gitignore`，不要把镜像包提交进仓库。

## 4. 启动

推荐走脚本（它会校验路径、等健康、失败自动 dump 日志）：

```bash
cp .env.example .env      # 填 DB、模型地址
bash scripts/docker-run.sh
```

### 手写的 docker run 完整命令

脚本实际执行的就是这条（已实测跑通）：

```bash
MSYS_NO_PATHCONV=1 docker run -d \
  --name ai-agent-java \
  --env-file .env \
  -e AGENT_WORKING_DIR=/workspace \
  -e AGENT_SKILLS_DIR=/skills \
  -p 8080:8080 \
  -v agent-workspace:/workspace \
  -v "$(pwd -W 2>/dev/null || pwd)/agent-skills:/skills:ro" \
  --restart unless-stopped \
  ai-agent-java:1.0.0
```

| 部分 | 作用 | 不能省的原因 |
|---|---|---|
| `--env-file .env` | 注入 DB / 模型地址 / 并发等全部应用配置 | 不给就用镜像默认值，会连不上库直接启动失败 |
| `-e AGENT_WORKING_DIR=/workspace` | 任务工作区根目录 | 不覆盖则沿用 `.env` 里的 `./agent-workspace`（本地开发习惯），容器里路径会变绕 |
| `-e AGENT_SKILLS_DIR=/skills` | 技能目录 | 与下面的 `-v ...:/skills:ro` 配套；不设则去找 `./agent-skills`，挂进去的技能加载不到 |
| `-v agent-workspace:/workspace` | named volume，任务产物与沙箱 | 不挂则重启即丢，且多实例无法共享 |
| `-v "<宿主机>/agent-skills:/skills:ro"` | **技能目录只读挂载** | 不挂就只能 `docker cp` 进容器，热加载优势作废 |
| `-p 8080:8080` | 对外端口 | — |
| `--restart unless-stopped` | 宿主机重启后自起 | — |
| `MSYS_NO_PATHCONV=1` | 只在 Git Bash 下需要 | 否则 `-e` 里的 `/workspace` 会被改写成 `D:/Program Files/Git/workspace` |

两个路径细节（都已实测）：

- **技能源路径必须写成宿主机真实路径**。Git Bash 里用 `$(pwd -W)` 取 `F:/...` 形式；`$(pwd)` 得到的 `/f/...` Docker Desktop 认不了。Linux/macOS 上 `pwd -W` 失败会自动退回 `pwd`。项目路径含中文也可以（实测 `F:/软件项目/...` 正常挂载）。
- **`:ro` 是真只读**，不是约定：容器内 `touch /skills/probe` 报 `Read-only file system`。技能属于部署配置，容器（尤其是带 shell 的 agent）不应该能改它。

技能挂载验证：

```bash
curl -s localhost:8080/api/v1/agent/skills        # 直接看真正加载到了哪些技能
# 宿主机拷一个进去，不用重启，下一个任务即生效：
cp -r docs/examples/skills/report-format agent-skills/
```

实测：拷入后 `GET /skills` 立即从 `["report-format"]` 变 `["legal-terms","report-format"]`；提交带 `"skills":["report-format"]` 的任务，输出里带上了技能规定的 `[REPORT-SPEC-v1]` 结尾标记。

脚本会：打印镜像 label → 重建同名容器 → **校验容器内 `AGENT_WORKING_DIR` 是绝对路径** → 等健康检查通过（失败自动 dump 日志）。

容器内约定：

- 工作根目录 `/workspace`（named volume `agent-workspace`），每个任务实际跑在 `/workspace/<taskId>`，shell 与文件工具的 cwd 就是它。实测模型报告的路径与 `docker exec` 查到的真实路径一致。
- `.env` 里的 `AGENT_WORKING_DIR=./agent-workspace` 保持不动：`docker-run.sh` 与 `docker-compose.yml` 都会显式覆盖为 `/workspace`。
- 健康检查 `GET /api/v1/agent/health`，`start-period=60s`（启动要连 MySQL、初始化表、拉 worker）。
- `docker stop` 发 SIGTERM，`ENTRYPOINT` 用 `exec` 保证 java 是 PID 1，能走优雅停机；被中断的任务由租约（默认 90 秒）到期后自动重跑。

docker-compose（含一套 MySQL 8.4，适合本地整栈起）：

```bash
APP_IMAGE=ai-agent-java:1.0.0 docker compose up -d
```

## 5. 踩过的两个坑（都已在脚本里处理）

1. **Git Bash 会把 `/workspace` 改写成 `D:/Program Files/Git/workspace`**。MSYS2 对以 `/` 开头的参数做 Windows 路径转换，实测 `docker run -e AGENT_WORKING_DIR=/workspace` 传出的是 `D:/Program Files/Git/workspace`；容器内 JVM 当作相对路径，于是在 `/workspace/D:/Program Files/Git/workspace/<taskId>` 下建目录，任务文件全部落到意外位置。`docker-run.sh` 已 `export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'`（Linux/macOS 上无害），并在启动后校验该值必须以 `/` 开头。
2. **本机服务锁住 jar 导致 `clean` 失败**。`scripts/run-local.sh` 起的进程用 `java -jar target/*.jar`，Windows 会锁文件，Maven clean 删不掉，报错是乱码的"另一个程序正在使用此文件"，容易被误判成代码问题。`docker-build.sh` 已在编译失败时提示这一原因；打镜像前先停本地服务。

补充：Dockerfile 顶部刻意不写 `# syntax=docker/dockerfile:1`——那会让 BuildKit 去 docker.io 拉 frontend，本机构建环境访问 `auth.docker.io` 超时，而本文件用到的指令内置 frontend 全支持。

## 6. 排障常用命令

```bash
docker logs -f ai-agent-java                                  # 应用日志
docker exec -it ai-agent-java bash                            # 进容器（python/curl/jq/rg 都在）
docker exec ai-agent-java printenv | sort                     # 确认生效的配置
docker exec ai-agent-java ls -la /workspace/<taskId>          # 某任务的工作目录产物
docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' ai-agent-java
```

临时跑一条命令要**覆盖 entrypoint**，否则参数会被拼到 `java -jar app.jar` 后面变成应用启动参数：

```bash
docker run --rm --entrypoint sh ai-agent-java:1.0.0 -c 'du -sh /workspace'
```

## 7. 上线前检查清单

- [x] `pom.xml` 版本去掉 `-SNAPSHOT`（现为 `1.0.0`）
- [ ] `.env` 从 `.env.example` 复制并填真实值；**`.env` 已被 `.dockerignore` 排除，绝不会进构建上下文**（这里挡的是凭据泄露，不是"反正没 COPY"）
- [ ] 模型端点与 MySQL 从**容器网络内**可达（`docker exec ai-agent-java curl -m 5 <模型地址>/v1/models`）
- [ ] 并发/队列/超时按容量核定：`AGENT_MAX_CONCURRENT`、`AGENT_MAX_QUEUED_TASKS`、`AGENT_DEFAULT_TIMEOUT`
- [ ] 镜像内存限制 ≥ 2GB（`JAVA_OPTS` 用 `MaxRAMPercentage=75`，Python+pandas 也要占堆外与 RSS）
- [ ] 确认服务边界：当前**无鉴权** + `permission-mode=BYPASS` + shell，等于把容器内的命令执行开放给能访问该端口的人。安全边界是"容器 + 每任务独立子目录"，不要把裸端口暴露到不可信网络；需要收紧时看 `agent.tools.shell-allowed-commands` 与权限模式
- [ ] `GET /api/v1/agent/health` 通过；`GET /api/v1/agent/tools` 返回的工具集与预期的 `agent.tools.*` 开关一致
