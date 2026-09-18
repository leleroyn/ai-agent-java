# 技能目录（运维挂载点）

服务启动时扫描这里，对应配置 `agent.skills.directory`（环境变量 `AGENT_SKILLS_DIR`）。
**本目录的内容属于部署产物，不要提交进版本库**，这里只保留本说明。

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
