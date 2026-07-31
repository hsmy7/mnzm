# 加载阶段后台任务架构

## 概览

游戏启动/读档时的加载阶段由 `BootSequenceController.boot()` 统一编排。

> **2026-08-01 文档同步**：旧文档声称"7 个独立模块并行执行 + 0-100% 分阶段进度"，
> 与实际实现不符——`boot()` 是**严格顺序的 8 步**（单协程），仅
> `ResourcePreloader` 内部的 dataInit/manualInit（2 路 async）与精灵解码
> （3 路 async）是真并行；地图瓦片生成是顺序内的单个 `withContext(Dispatchers.Default)`。
> 各模块（快照预热/存档校验/图集约/字体/音频）由 8 步顺序调用，加载界面进度条
> 按步骤推进（非并行合并进度）。

## 实际执行顺序（BootSequenceController.boot()，2026-08-01 核对）

```
第 1 步  建筑修正        修复旧存档占地重叠/越界建筑（applyBuildingMigrationOnEngine）
第 2 步  数据迁移        migrateSaveDataIfNeeded（saveVersion 顺序迁移）
第 3 步  资源预加载回调  onPreloadResources（ResourcePreloader：
                          dataInit/manualInit 2 路 async + 精灵解码 3 路 async）
第 4 步  弟子快照预热    DiscipleSnapshotCache.prewarm()（O(n) 遍历）
第 5 步  重型数据+完整性 ensureHeavyDataLoaded + SaveValidator 规则引擎校验
                         （20 条规则：引用完整性/幽灵清理/负数截断等，自动修复）
第 6 步  分配门重建      DiscipleAssignmentGate.rebuildFromGameData（11 槽位注册表）
第 7 步  游戏循环启动    startGameLoop（GameEngineCore）
第 8 步  地图生成        SectMapTileGenerator（withContext(Dispatchers.Default) 单任务）
```

### 并行点（实际存在的并发）

| 位置 | 并行度 | 说明 |
|------|--------|------|
| `ResourcePreloader.preloadGameResources` | 2 async | dataInit（Config/ManualDB）与 manualInit 并行 |
| `ResourcePreloader` 精灵解码 | 3 async | L0 头像/UI + L1 物品 + AtlasPacker 图集打包并行 |
| `GameActivity` Vulkan 预热 | 1 后台 | prewarmDevice 后台执行 + 5s 超时（write-ahead 标记防 SIGSEGV 残留） |

## 模块索引（按步骤挂载）

### 存档完整性校验（第 5 步）

- `core/data/.../integrity/SaveValidator.kt` — 规则引擎（2026-08-01 实测 20 条规则，非旧文档"6 项检查"）
- 校验项：名称空值修复 | 日期越界截断 | 修为超限截断 | 装备/建筑孤立引用清理 | 幽灵弟子/引用清理 | 负数灵石截断 | 年龄超寿命截断 | 重复 ID 去重 等

### 弟子属性快照（第 4 步）

- `core/domain/.../model/DiscipleSnapshot.kt` — 快照数据类
- `core/engine/.../disciple/DiscipleSnapshotCache.kt` — 加载阶段预热，O(1) Map 查询
- 集成点：`BootSequenceController.boot()` 第 4 步

### 建筑修正（第 1 步）

- `SaveLoadLoadDelegate.migrateOverflowBuildings` 计算（纯函数）+ `GameEngine.applyBuildingMigrationOnEngine` 状态应用（2026-08-01 引擎线程入口）

### Compose 精灵图集约（第 3 步）

- `core/ui/.../components/AtlasPacker.kt` — shelf packing 算法，1024×1024 图集
- `core/ui/.../components/AtlasSpriteImage.kt` — 图集渲染 Composable，带回退
- 集成链路：`ResourcePreloader` → `PreloadResult.itemAtlas` → `LocalAtlasCache` (CompositionLocal)

### Vulkan 预热写前保护

`GameActivity.kt` + `CrashRecoveryEngine.kt`：write-ahead 标记（markPrewarmStarted → prewarmDevice → clearPrewarmStarted），SIGSEGV 残留标记下次启动直接禁用 Vulkan。

### 字体预渲染

- `core/ui/.../util/FontPreloader.kt` — Typeface 预初始化
- `core/ui/theme/Typography.kt` — 自定义字体引用，优雅回退 SansSerif

### 音频引擎

- `core/engine/.../audio/AudioEngine.kt` — SoundPool(8路) + MediaPlayer
- `core/engine/.../audio/AudioConfig.kt` — 音量/静音管理
- `core/engine/.../audio/AudioPreloader.kt` — 预加载扩展点

## 关键文件索引

| 模块 | 关键文件 |
|------|---------|
| 编排 | `BootSequenceController.kt`（boot() 8 步顺序） |
| 资源预载 | `ResourcePreloader.kt`（唯一并行点） |
| 存档校验 | `SaveValidator.kt`, `StorageEngine.kt` |
| 图集约 | `AtlasPacker.kt`, `AtlasSpriteImage.kt` |
| 建筑修正 | `SaveLoadLoadDelegate.kt`, `GameEngineCoordination.kt` |
| 弟子快照 | `DiscipleSnapshot.kt`, `DiscipleSnapshotCache.kt` |
| 字体 | `FontPreloader.kt`, `Typography.kt` |
| 音频 | `AudioEngine.kt`, `AudioPreloader.kt`, `AudioModule.kt` |
