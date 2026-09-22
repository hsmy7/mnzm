# AGENTS.md — 文档维护（docs/）

> 本文件是 `docs/` 目录的维护约定。**通用规范见仓库根 `AGENTS.md`。**
>
> 模块级 `AGENTS.md` 应保持精简：从子目录用 Codex 启动时，「root → cwd」链上各文件会**合并**计算
> `project_doc_max_bytes`（默认 32768 字节），过大的模块文件会把根文件挤掉。门禁规则⑤ 会告警最坏链路。

## 目录性质

| 路径 | 性质 | 维护方式 |
|---|---|---|
| `architecture.md` / `knowledge-base.md` / `cpp-engine.md` | **活文档**（描述当前状态） | 代码变更后同步更新 |
| `ui-read-surface.md` / `threading-contract.md` / `platform-abilities.md` | 契约文档 | **变更前先登记，再实现** |
| `adr/` | 架构决策记录（Context / Decision / Consequences） | 追加式，不改写历史 |
| `parallel-batches*/` | 批次派工与验收档案 | 过程记录，内部引用失效属正常 |
| `design/` · `build-perf/` · `research/` | 专题方案与调研 | 一次性产出，一般不回改 |

## 文档同步义务

- 新增模块 / 模式变更 → 同步 `CODE_WIKI.md` 与 `docs/architecture.md`
- 新增架构决策 / 大重构 → 在 `docs/adr/` 写 ADR
- 功能完成 → **两个更新日志必须一起更新**（`CHANGELOG.md` + `android/app/src/main/assets/changelog_entries.json`），
  流程见 `rules/version-release.md`
- 注释与文档只描述**当前状态**：禁止「之前/原来/新增/删除/迁移」等历史性表述、已解决 TODO、
  旧架构描述、AI 工作汇报式注释 —— 七项检查清单见 `rules/code-comment.md`

## 引用路径规范（本项目实测踩过的坑）

写文档内引用时遵守以下四条，否则 agent 按引用去读会扑空：

1. **写成可解析的路径，不要用裸文件名** —— 不带目录的裸文件名（如 game-data.json）应写全为
   `android/app/src/main/assets/data/game-data.json`
2. **注意同名概念文档的前缀** —— `rules/`、`docs/` 顶层与 `android/docs/` 下存在同名文档，
   最常见的错误是丢掉 `android/` 前缀（把 renderer-feature-checklist.md 误算作 `docs/` 下的文件）
3. **禁止用 `../../` 越出仓库根** —— 根目录与浅层目录里写 `../..` 会解析到仓库之外
4. **外部仓库 / 参考资料必须写完整 URL** —— 不要写成看起来像仓内路径的裸路径
   （如 Flutter 的 impeller/docs/android.md、GPU 厂商问题列表 gpu_driver_bug_list.json，都应给出来源 URL）

> 门禁会校验路由闭包内的引用：`node scripts/check-agent-instructions.mjs`

## DSH skill 接入（.agents/skills）

项目积累的 Android / Compose 专项 skill 存放在 `.claude/skills/`（61 个），但 DSH 的 skill 搜索路径
（`<repo>/.dsh/skills`、`<repo>/.agents/skills`、`~/.dsh/skills`、`~/.agents/skills`）不覆盖该目录。
仓库通过 `.agents/skills` → `.claude/skills` 的**目录联接（junction）**把它们接入 DSH；
该路径已加入 `.gitignore`（联接是本机产物，不入库）。

**换机器 / 重新 clone 后需重建**（Windows PowerShell）：

```powershell
New-Item -ItemType Directory -Path .agents -Force
New-Item -ItemType Junction -Path .agents\skills -Target (Resolve-Path .claude\skills).Path
```

注意：`New-Item -ItemType Junction` 的 **Target 必须是绝对路径**——传相对路径会报「创建交汇点需要目标的绝对路径」。
