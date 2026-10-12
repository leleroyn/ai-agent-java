# 技能目录（运维挂载点）

服务启动时扫描这里，对应配置 `agent.skills.directory`（环境变量 `AGENT_SKILLS_DIR`）。

**入库约定**：本目录平时是**部署产物**（各环境的 skill 由运维拷入，不提交，此处默认只保留本说明）。通用的、可复用的示例 skill 模板放 `docs/examples/skills/`（已入库）。

> 印章 / 发票 / 身份证 / 营业执照**是技能**（`seal-recognition` / `invoice-recognition` /
> `idcard-recognition` / `bizlic-recognition`），但它们**随 jar 内置**
> （`ai-agent-app/src/main/resources/skills/`），不放这个目录。
> 中间反复过一轮：早先印章/发票是内置工具 `recognize_seal` / `recognize_invoice`，已删除——两套口径会漂移，
> 而镜像里的 `ips seal` / `ips invoice` / `ips idcard` / `ips bizlic` 已经能给出公司名与全部字段
> （身份证/营业执照从一开始就没做过内置工具，直接走技能）。技能正文只写「调哪条命令、
> 返回怎么读、什么算终局结论」。注意 `idcard`/`bizlic` 只收图片，PDF 要走 `extract_document_fields`。
>
> 两个坑，改技能前先记住：
> 1. 指令里的文件 URL 已在任务启动前由 `SourceIngestor` 下载到 `src/`，工具与 CLI 都只吃本地路径。
>    技能正文若让模型先 `curl -o` 下载，同一份内容就会出现「URL」和「本地副本」两个身份，去重失效
>    （实测把 72 页重新 OCR 一遍）；`curl`/`wget` 现在也被命令黑名单直接拒掉。
> 2. 这几项能力依赖 `AGENT_TOOL_SHELL=true`。shell 关掉时技能正文会被加载但命令跑不了，
>    模型只能空转到迭代上限——环境开关要和技能集一起看。要收紧 shell 就用逐段白名单
>    `AGENT_SHELL_ALLOWED=ips`（管道仍可用，`cat`/`grep` 之类全被拒），别直接关。

## 放法

```
agent-skills/
├── <技能名>/
│   ├── SKILL.md          # 必需，入口
│   ├── references/       # 可选，参考文档
│   ├── examples/         # 可选
│   └── scripts/          # 可选，需 code-execution-enabled=true 才会被执行
└── <另一个技能>/
    └── SKILL.md
```

`SKILL.md` 必须有 YAML frontmatter：

```markdown
---
name: 技能名              # 需与目录名一致，匹配 [A-Za-z0-9][A-Za-z0-9._+-]{0,63}
description: 一句话说明什么情况下该用这个技能
---

正文 = 模型加载后必须遵循的规范。写清楚硬性要求（格式、字段、禁止事项），
避免写背景介绍——正文只在被显式指定时注入，或在模型判断匹配时按需加载。
```

## 生效方式

改完**不需要重启**，下一个任务就会读到（按 SKILL.md 的 mtime/size 快照）。
新增技能同理，拷进来即可，`GET /api/v1/agent/health` 的 `skills` 字段会立刻列出。

## 想先试一下

可运行示例在 `docs/examples/skills/`，拷一份过来就能用：

```bash
cp -r docs/examples/skills/report-format agent-skills/
curl -s localhost:8080/api/v1/agent/health   # skills 里应出现 report-format
```

## 优先级

本目录的技能**覆盖** jar 内置（`src/main/resources/skills`，配置
`agent.skills.classpath-location`）的同名技能。因此可以在不重新打镜像的前提下
修正一个内置技能的措辞。
