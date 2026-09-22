# 模拟宗门（mnzm）

> 修仙宗门模拟经营手游（Android）。Kotlin/Compose 负责界面，**C++20 确定性游戏引擎核心 `game-core`** 承载模拟逻辑，两者经 JNI 对接；C++ 是模拟真相源，Kotlin 侧为只读镜像 + 平台效应。

[![Android](https://img.shields.io/badge/Android-minSdk%2024-3DDC84?logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.20-7F52FF?logo=kotlin&logoColor=white)](#)
[![C++](https://img.shields.io/badge/C%2B%2B-20-00599C?logo=cplusplus&logoColor=white)](#)
[![License](https://img.shields.io/badge/license-Proprietary-lightgrey)](#许可)

- 应用包名：`com.xianxia.sect`　｜　版本：`version.properties`（单一事实源）
- 隐私政策（线上）：<https://hsmy7.github.io/mnzm/>

---

## 技术栈

| 层 | 技术 |
|---|---|
| 界面 | Jetpack Compose（Material 3）、Hilt、Navigation（MainTab + Dialog，无 NavHost） |
| 引擎核心 | **C++20**（`game-core`，零 Android 依赖、桌面可编译）、CMake/NDK、JNI + `nlohmann::json` |
| 数据 | Room（含 Migration 链）、kotlinx.serialization ProtoBuf、MMKV、EncryptedSharedPreferences |
| 渲染 | Vulkan + GLES 双路径，另有软件渲染回退（`SoftwareCanvasBackend`） |
| 平台 | TapTap SDK（登录 / 防沉迷实名）、TapADN 聚合广告、TapDB 数据分析 |

## 架构要点

- **双层状态模型 + Frame-Driven 游戏循环**：`GameStateStore` 是 UI 侧唯一真相源（镜像），引擎层 C++ 独占 tick/旬结/月结/年结。
- **惰性结算引擎**：四层推进（时间推进 → 每旬检查 → 月变 → 年变），对标 Supercell + RimWorld。
- **确定性 RNG**：4 分区 PRNG（BATTLE / BREAKTHROUGH / EXPLORATION / SYSTEM）双端同源，跨语言逐位对拍。
- **C++ 迁移进行中**：模拟核心 / 战斗 / 生产 / 探索 / 内政 / 经济 / 外交 / 秘境 / 地图生成等已 C++ 化；当前主轴是「UI 操作面逐域收尾 → 删除反向同步通道」。
- **一致性保障**：跨语言对拍（`Diff*` 测试）、守卫测试（RNG 来源 / 动作分派覆盖 / detekt baseline 只缩不增 / 反向通道分类穷尽）进 CI。

## 目录结构

```
android/
  app/                  应用壳 + JNI 桥 + 原生资源
    src/main/cpp/
      gamecore/         ★ C++20 引擎核心（game-core，零 Android 依赖）
        include/gamecore/{system,state,ecs,map,...}
        src/            · execute_dispatch.cpp：ActionId 分发表
                        · dispatch_w4{a,b,c}.cpp：并行批次分派端口
        test/           GTest（桌面 ctest）
  core/domain/          纯 Kotlin 领域模型（零 Android 依赖）
  core/data/            Room / 序列化 / 加密 / Repository 实现
  core/engine/          引擎服务、系统、桥接（GameEngine / GameStateStore / StateSyncService）
  core/ui/              共享 Compose 组件与主题
  feature/game/         ViewModel + Screen 级 Compose + 对话框
  detekt-rules/         自研 detekt 规则
  build-logic/          构建约定插件
docs/                   架构 / ADR / 迁移进度 / 审计报告（见下）
rules/                  编码与领域规则（20 篇）
scripts/                codegen / 桌面构建 / 仓库与发布工具
```

## 构建与测试

> 所有命令的工作目录为 `android/`（Gradle 根）。

```bash
# 编译检查（最快的反馈）
./gradlew.bat compileReleaseKotlin

# 构建
./gradlew.bat assembleDebug          # 调试包
./gradlew.bat assembleRelease        # 发布包

# 单元测试（必须串行：并行会因共享静态状态跨类污染）
./gradlew.bat testReleaseUnitTest --max-workers=1
./gradlew.bat :app:testReleaseUnitTest --tests "com.xianxia.sect.core.engine.BattleSystemTest" --max-workers=1

# C++ 引擎测试（桌面 GTest；需 llvm-mingw 的 bin 在 PATH）
cd app/src/main/cpp/gamecore/build/desktop-test && cmake --build . && ctest

# 跨语言对拍（先重建桌面 JNI，脚本在仓库根 scripts/）
pwsh -File ../scripts/build-desktop-jni.ps1
./gradlew.bat :core:engine:testReleaseUnitTest --rerun-tasks --max-workers=1 \
  "-Dgamecore.jni.path=<仓库绝对路径>/android/core/engine/build/desktop-jni/libgamecorejni.so"

# 静态检查与提交门
./gradlew.bat detekt
./gradlew.bat lintRelease
./gradlew.bat :app:externalNativeBuildRelease     # NDK arm64
```

## 文档索引

| 文档 | 内容 |
|---|---|
| [`AGENTS.md`](AGENTS.md) | 唯一规范入口：协作公约、编码规范、任务路由表（专题规则见 `rules/`） |
| [`docs/architecture.md`](docs/architecture.md) | 整体架构设计 |
| [`docs/knowledge-base.md`](docs/knowledge-base.md) | 技术栈、关键类、各系统现状 |
| [`docs/adr/`](docs/adr) | 架构决策记录（13 篇，含 C++ 引擎迁移总方案、反向通道根治、随机源治理） |
| [`docs/cpp-engine.md`](docs/cpp-engine.md) | C++ 迁移进度与剩余工作 |
| [`docs/cpp-migration-handover-m0.md`](docs/cpp-migration-handover-m0.md) | 迁移批次对接文档（已完成批次的硬规格 / 红线 / 遗留待办） |
| [`rules/`](rules) | 编码与领域规则 |
| [`CHANGELOG.md`](CHANGELOG.md) | 开发者向更新日志 |
| 游戏内更新日志 | `android/app/src/main/assets/changelog_entries.json` |

## 分支与历史

| 分支 | 说明 |
|---|---|
| **`main`** | **主开发线**（默认分支）。承载模拟核心与 C++ 迁移的全部工作 |
| `master` | **历史归档**：2026-03 ～ 06 的 1.4.x 线。**不再更新**，保留仅为追溯 |

> **2026-09-15 历史统一说明**：仓库曾因 `.git` 对象库两次被破坏而重建过一条本地历史线（26 个提交，作者 `mnzm-dev`，与远端真实历史无共同祖先）。现已用一个双亲合并提交 `ba69918` 把两条线统一：**第一父 = 远端 1834 个提交的真实历史，第二父 = 重建线**，树取当前工作区（内容零变更、**未使用 force-push**、两侧对象全部保留）。此后 `main` 为唯一主线。详见合并提交说明与 `docs/cpp-migration-handover-m0.md` §2.67。

## 许可

本仓库**未声明开源许可**（保留所有权利）。第三方 SDK 与开源组件（TapTap SDK、MMKV、Room、Hilt、Compose、googletest、nlohmann/json 等）遵循各自许可。
