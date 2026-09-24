# AGENTS.md — 素材与音频源文件目录（media-source-assets）

> 本文件登记**项目指定的美术素材源目录与音频源目录**的位置、构成、转化流程与使用边界。
> **通用规范见仓库根 `AGENTS.md`；素材进包的七步流程唯一真源是 `rules/static-resources.md`。**
> 本文件只回答「原始素材放在哪、谁可以读它、它和包内资源是什么关系」。

## 1. 指定源目录（2026-09-24 用户拍板）

| 目录（仓库根相对路径） | 体量（实测） | 内容 | 用途 |
|---|---|---|---|
| `模拟宗门美术素材/` | **572 MB / 385 文件** | 384 张 PNG + 1 个 mp4（见 §6） | 角色、装备、UI、建筑、装饰物、背景图的**唯一原始素材来源** |
| `模拟宗门音乐音效/` | **1.6 MB / 2 文件** | 2 个 mp3 | 游戏背景音乐与音效的**唯一原始音频来源** |

目录结构（按角色/类别分子目录，命名含性别标注，例：`许荷（女）/头像.png`、`林雪棠（女)/全身像.png`）：

```
模拟宗门美术素材/
  <角色名>（性别）/头像.png · 全身像.png     ← 立绘类，逐角色一目录
  装备/  ui/  弟子肖像图/  背景图/  装饰物/   ← 批量类
  视频/                                       ← 非素材，见 §6
```

⚠️ **目录名含中文与全/半角括号混用**（`（女）` 与 `（女)` 同时存在）。批量脚本按目录名匹配时**必须先归一化**，否则漏配。

## 2. 🔴 版本库策略：指定源目录**不入库**

两个目录**已写入 `.gitignore`**（`/模拟宗门美术素材/`、`/模拟宗门音乐音效/`），属**仅本机资产**：

- 不提交、不推送、不进任何批次的 diff；`git status` 中不应再出现它们。
- **换机器 / 重新 clone 后这两个目录是空的**，需按项目外的资产分发渠道另行同步（同 `.agents/skills` 联接的重建性质，见 `docs/AGENTS.md`）。
- 理由：美术源图 572 MB / 384 张 PNG，仓库 `.git` 现已 1.5 GB；且源图未做无损 WebP 转化，入库即与 §5 编码规范 6.6「禁止提交 PNG/JPG 游戏图片」的取向冲突。
- ⇒ 任何批次的「未跟踪 4 组永不提交」人工提醒**自本文件起作废**，改由 `.gitignore` 强制。

## 3. 🔴 与包内资源的边界（不可混淆）

**源目录 ≠ 应用资源目录。** 应用运行时**只允许**读取 `android/*/src/main/res/drawable*/` 与
`android/app/src/main/assets/`，且必须经 `SpriteResRegistry` / `SpriteImage("名称")` /
Canvas `drawSprite(name, cache, ...)` / `SpriteResRegistry.resolve("名称")` 访问。

| 规则 | 说明 |
|---|---|
| 源目录文件**永不**被代码按路径直接读取 | 不经注册表、不拼路径读文件；源图仅供人工/转换脚本消费 |
| 源图进包**必须**转无损 WebP + 双模块放置 + 注册 | 全流程七步见 `rules/static-resources.md`；**源目录里的 PNG 不是**「禁止提交 PNG/JPG 游戏图片」红线的例外，那条红线约束的是**包内资源** |
| 源目录**不参与** APK 构建 | 不在 `assets/`/`res/` 下，Gradle 不会打包，不影响包体 |
| 音频进包同规则 | 源 mp3 须经 `rules/static-resources.md` 的音频落点与注册流程，不在本文件重复 |

## 4. 角色寻访（卡池重构）与素材键的关系

G01 已在 `android/app/src/main/assets/data/game-data.json` 登记 12 个角色精灵键
（`avatar_*` / `portrait_*`，如 `avatar_zhouming` / `portrait_zhouming`），但这 12 个键
**尚未注册进 `SpriteResRegistry`** —— `android/scripts/resource-registry.json` 无、
`android/scripts/sprite-uid-map.json` 无、双模块 `drawable-nodpi` 无文件。该缺口是 **G11 最简寻访 UI 的硬阻塞**，由 **G16 素材批**处理：

```
G16：从 模拟宗门美术素材/<角色名>/ 取源图 → 无损 WebP → 双模块放置 →
      XianxiaApplication.kt 经 SpriteResRegistry.register(...) →
      ResourcePreloader 同步点 → 图集 codegen → 守卫测试
```

## 5. 构建副产物提示

改动素材会连带刷新 `android/app/src/main/assets/atlas/atlas-rgba-manifest.json`、
`android/scripts/sprite-uid-map.json`、`android/app/src/main/cpp/scene/scene_uv_tables.h` 等**构建副产物** —— 这些是每次构建都会变时间戳的产物，
**提交前 `git checkout --` 还原**（例外：本批确实改了图集定义时，生成物须与源改动同批提交）。

## 6. ⚠️ 非素材文件登记

`模拟宗门美术素材/视频/8月16日.mp4`（16 MB）**不是游戏素材**，是一次性录屏/演示视频。
它不属于本文件定义的素材域，任何批次不得引用、不得进包；保留在源目录仅作历史资料。
