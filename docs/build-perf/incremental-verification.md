# 跨模块增量互删验证（2026-08-14）

> 阶段 1.4 产物。2024 年 CI 曾因"跨模块增量导致 build 目录互删"而全冷
> （`--no-daemon -Pkotlin.incremental=false` 的历史由来）。本次在
> **Kotlin 2.2.20 + AGP 8.10.0** 下重新验证，决定 CI 是否恢复增量。

## 验证方法

1. 全量 `:app:assembleDebug` 建立编译状态（首次 8m27s，100 executed + 64 from-cache）
2. 快照 6 个模块 `build/` 目录存在性
3. 向 `core/domain` 一个 main 文件追加注释（探针）→ 增量构建 → 检查重编译范围 + build 目录
4. 还原，向 `core/engine` 一个 main 文件追加注释 → 同上
5. 探针还原（`git checkout`），工作区恢复干净

## 结果

| 步骤 | 重编译范围 | 其他模块 build 目录 | 结论 |
|---|---|---|---|
| 改 core/domain | 仅 `core:domain:compileDebugKotlin` 执行；engine/data/ui/feature/app 均 UP-TO-DATE（ABI 未变，Kotlin 2.x ABI 级增量） | 全部存在 | ✅ 无互删 |
| 改 core/engine | 仅 `core:engine:compileDebugKotlin` 执行；feature/game + app UP-TO-DATE（ABI 未变） | 全部存在 | ✅ 无互删 |

- 二跑（无探针）全 UP-TO-DATE，构建 5s 内完成
- 中间结论：**Kotlin 2.2.20 下"跨模块增量互删 build 目录"未复现**

## 结论与 CI 决策

- ✅ **CI 恢复增量安全**：移除 `-Pkotlin.incremental=false`（ci.yml 已改：compile step 不再传该属性）
- ✅ **保留 `--build-cache`**：本地验证 64/170 任务命中缓存，远程缓存（CI → 开发者）收益真实存在
- ⚠️ **注意点**（已记录，非阻塞）：
  - Kotlin 增量对"生成文件缺失→重新出现"的跨状态变化有已知盲区（见 baseline 3.2 节 codegen 死锁），需一次非增量编译恢复快照——仅影响本地生成物被误删的极端场景，CI 全冷不受影响
  - ABI 级增量的含义：改实现不改签名 → 下游模块不重编译（快）；改签名 → 下游正常重编译（正确）

## 验证环境

- Kotlin 2.2.20、AGP 8.10.0、Gradle 8.14.5、KSP 2.2.20-2.0.4
- 内存档位：Gradle 6G + Kotlin daemon 4G（32GB 开发机）
- 复现条件相同（同 JDK、同 Gradle 版本）下，CI 行为应与本地一致
