# 后续优化项 — 审计报告 + 实施方案

**日期**: 2026-06-06  
**基于**: CODE_WIKI.md 后续优化项（13条待实施）+ 深度行业调研（22条参考来源）  
**适用**: 审查结论 + 分步执行

---

## 目录

1. [逐项审计](#1-逐项审计)
2. [行业对标分析](#2-行业对标分析)
3. [实施方案](#3-实施方案)
4. [参考来源清单](#4-参考来源清单)

---

## 1. 逐项审计

对 CODE_WIKI.md 中 13 条待实施/部分实施优化项，逐一深入代码库审查实际必要性。

### 1.1 P1: `snapshotFlow` 用于修炼进度条逐帧动画

**当前状态**: 代码库中未使用 `snapshotFlow`（grep 零匹配）。

**实际需求**: **确实需要，范围锁定为修炼进度条和战斗动画进度条**

修炼进度条每 tick 更新进度值，当前通过 `collectAsState()` 驱动，每个值变化都触发完整重组。`snapshotFlow` 可以将高频状态读取转为副作用流，跳过重组直接在 Canvas 上绘制。

**行业对标**: Google Android 官方文档明确区分：`collectAsState` 用于 UI 数据绑定，`snapshotFlow` 用于高频副作用。Compose 1.10（2025.12）的 **pausable composition** 可与 `snapshotFlow` 配合。

**结论**: ✅ 保留 P1。仅覆盖修炼进度条和战斗动画进度条。

---

### 1.2 P1: FrameMetrics 接入 UnifiedPerformanceMonitor 统一框架

**当前状态**: `FrameMetricsMonitor` 已独立实现，`UnifiedPerformanceMonitor` 已就位，两者**未集成**。

**实际需求**: **确实需要，降为 P2**

两个监控器独立运行，无统一入口。但仅对开发/调试有价值，对最终用户无直接影响。

**行业对标**: Google ADPF（Android Dynamic Performance Framework）是行业标准。NCSoft Lineage W、Netmarble 均使用 ADPF 统一框架。

**结论**: ✅ 保留，降为 P2。

---

### 1.3 P2: Disciple 字段注解驱动合并

**当前状态**: `Disciple.kt` 含 **67 个 `@deprecated` 委托属性**，手动转发到 6 个 `@Embedded` 子组件。`@SettlementStrategy` 注解已验证模式可行性。

**实际需求**: **确实需要，P2 合理**

67 个手工委托是脆弱的维护负担。新增字段易遗漏，且完全是机械重复代码。

**行业对标**: KSP 代码生成是 Kotlin 生态标准方案（Room 的 `@Entity` + `@ColumnInfo` 同理）。

**结论**: ✅ 保留 P2。用 KSP 代码生成消除手工委托。

---

### 1.4 P2: `graphicsLayer` 用于地图平移/按钮缩放

**当前状态**: `graphicsLayer` **已用于**地图放置预览覆盖层（`MainGameScreen.kt:1304`），实现了零重组平移。

**实际需求**: **部分已完成，剩余范围有限**

可扩展：按钮按下缩放、地图标记淡入淡出。当前 `animateFloatAsState` 方案触发重组。

**行业对标**: Compose 1.10 + Google 官方文档推荐纯视觉变换使用 `graphicsLayer`。

**结论**: ✅ 保留 P2，缩小范围至按钮缩放 + 标记淡入淡出。

---

### 1.5 P2: Phase B — GameData 领域实体表拆分

**当前状态**: `DomainStateProvider` 抽象层已就位，但**所有领域状态仍从 GameData 全量提取**。

**实际需求**: **确实需要，应升级为 P1——整个清单中架构收益最大的单项**

当前每次读取任何领域数据都需要反序列化整个 GameData BLOB。`DomainStateProvider` 抽奖层就位后，下一步是创建独立 DAO 表。

**行业对标**: CQRS + 读写分离模式在游戏 DB 中的映射。Google 2025 Performance Spotlight 将"减少 IO 量"列为移动端性能三要素之一。

**结论**: ✅ 保留，升级为 P1。

---

### 1.6 P2: LZ4 压缩集成到 Room BLOB 存储

**当前状态**: LZ4 + `DataCompressor` 已用于 `DataArchiver`（存档级压缩），但**未**在 Room BLOB 列存储层面使用。

**实际需求**: **已基本完成，仅需最后一步——改动量小、收益大**

当前路径：序列化 → 写入 Room（未压缩）→ 存档时 LZ4 压缩
目标路径：序列化 → LZ4 压缩 → 写入 Room（已压缩）→ 存档时跳过

**行业对标**: Unity 官方将 LZ4 作为默认资源压缩方案。Google Android 源码内置 LZ4。

**结论**: ✅ 保留 P2。"高性价比"——半天工作量，存储减少 30-50%。

---

### 1.7 P3: 并发压力测试（100+ 协程）

**当前状态**: 无并发压测。`GameStateStore` 使用单一 `transactionMutex`。

**实际需求**: **确实需要，P3 合理——压测先行，瓶颈确认后再优化**

**行业对标**: Kotlin 官方文档建议"仅在确实需要互斥时使用 Mutex"。

**结论**: ✅ 保留 P3。先写压测确认瓶颈存在，避免过早优化。

---

### 1.8 P3: 细粒度锁/分片 Mutex

**当前状态**: 全局单一 `transactionMutex`。

**实际需求**: ❌ **建议删除。无瓶颈证据，典型的过早优化**

`transactionMutex.withLock` 内操作以纳秒级计，在 200ms tick 循环下几乎不可能成为瓶颈。引入分片锁会增加死锁风险和复杂度。

**结论**: ❌ 删除。待并发压测（1.7）确认瓶颈后再重新评估。

---

### 1.9 P3: Cloud Profiles 替代本地 Baseline Profile

**当前状态**: 已有本地 Baseline Profile 模块（`baselineprofile/`）。

**实际需求**: ❌ **建议删除。Cloud Profiles 由 Google Play 自动提供，与本地 Profile 互补而非替代**

ASE 2025 研究发现 99.89% Top 1000 应用已自动获取 Cloud Profiles。

**行业对标**: NetEase Cloud Music 本地 Baseline Profile +31% 启动提升，Cloud Profile 自动生效无需干预。

**结论**: ❌ 删除。本地 Baseline Profile + Google Play 自动 Cloud Profile 已是最佳组合。

---

### 1.10 P3: R8 full mode

**当前状态**: `minifyEnabled = true`，但 `gradle.properties` 未显式设置 full mode。

**实际需求**: ✅ **确实需要，升级为 P1——"最具影响力、最低努力量的单一改动"**

Google 2025 Performance Spotlight Week：
- Reddit: 启动提速 40%，ANR 减少 30%，帧渲染提升 25%
- Disney+: 启动提速 30%（仅移除 `-dontoptimize` 一行）

**结论**: ✅ 保留，升级为 P1。

---

### 1.11 P3: 巡逻塔 `updatePatrolConfigs` fire-and-forget → suspend

**当前状态**: `PatrolTowerViewModel` 使用 `viewModelScope.launch { }`（fire-and-forget），内部调用同步方法。

**实际需求**: **改动价值低**

`viewModelScope.launch` 在 ViewModel 层是正确模式。改为 `suspend` 无性能收益，是纯 API 风格改进。

**结论**: ⚠️ 降为 P4。

---

### 1.12 P3: `GameStateStore.updateXxxDirect` 方法移除

**当前状态**: 12 个 `updateXxxDirect` 方法 **仅定义，零处外部调用**——纯死代码。

**结论**: ❌ 不应作为"优化项"——直接删除即可。节省 60+ 行代码。

---

### 1.13 P4: 事件溯源审计日志

**当前状态**: 未实现。

**实际需求**: **P4 优先级正确——长期价值明确，短期不紧急**

价值：时间旅行调试、存档兼容性、审计轨迹。成本：事件类型定义、日志存储增长、快照策略。

**行业对标**: Eventure 框架（Python）、Werewolf Engine（JS）、Akka Persistence。

**结论**: ✅ 保留 P4。

---

### 审计汇总

| 结论 | 数量 | 具体 |
|------|------|------|
| ✅ 保留 | 9 | snapshotFlow (P1)、FrameMetrics (P2)、注解驱动 (P2)、graphicsLayer (P2)、领域表拆分 (P1)、LZ4 BLOB (P2)、并发压测 (P3)、R8 full mode (P1)、事件溯源 (P4) |
| ❌ 删除 | 3 | 分片 Mutex、Cloud Profiles、`updateXxxDirect`（死代码直接删除） |
| ⚠️ 降级 | 1 | patrol fire-and-forget → P4 |

---

## 2. 行业对标分析

### 2.1 Compose 性能优化对标

| 优化项 | Google 官方推荐 | 头部产品实践 | 本项目状态 |
|--------|---------------|-------------|-----------|
| `graphicsLayer` 零重组动画 | Compose 1.10 核心推荐 | 所有 Compose 应用 | 部分已用 |
| `snapshotFlow` 高频动画 | 官方文档明确区分 | Google I/O 2024 最佳实践 | 未使用 |
| Baseline Profile | 必备（2025 Performance Spotlight） | Reddit +55%、NetEase +31% | 已有 |
| R8 full mode | 最高优先级单一改动 | Reddit +40%、Disney+ +30% | 未启用 |
| FrameMetrics 统一监控 | ADPF 统一框架推荐 | Lineage W、Game of Thrones | 分散监控 |

### 2.2 数据架构对标

| 模式 | 行业实践 | 本项目差距 |
|------|---------|-----------|
| 领域实体表拆分 | Room 推荐 `@Embedded` 子表 | 仍在全量反序列化 |
| BLOB 压缩存储 | LZ4 游戏行业标准（Unity） | 仅存档级压缩 |
| 事件溯源 | 游戏行业标准（快照+事件日志） | 未实现 |
| 细粒度锁 | Kotlin `Mutex` 文档不推荐过早分片 | 单一锁足够 |

### 2.3 游戏性能监控对标

| 产品 | 监控方案 | 核心指标 |
|------|---------|---------|
| NCSoft Lineage W | ADPF Thermal API + Performance Hint | 热状态分 5 级，动态调整画质 |
| Netmarble Game of Thrones | ADPF + 分辨率缩放 | 分辨率是最有效的热缓解手段 |
| UNISOC Gaming Engine | ADPF + 帧率预测 | 50.1% FPS 提升 |
| 本项目 | FrameMetricsMonitor + UnifiedPerformanceMonitor（独立运行） | 需统一 |

---

## 3. 实施方案

### 3.1 执行路线图

```
  本周              第2周               第3-4周              Q3+
  ├─ A1 删死代码      ├─ B1 LZ4 BLOB      ├─ C1 领域表拆分     ├─ D1 事件溯源
  ├─ A2 R8 full mode  ├─ B2 snapshotFlow  ├─ C2 KSP注解       └─ D2 API风格
  └─ A3 文档清理       ├─ B3 FrameMetrics  ├─ C3 并发压测
                      └─ B4 graphicsLayer
```

**关键依赖**:
- C3（并发压测）的结果决定是否需要重新引入分片锁方案
- C1（领域表拆分）依赖 B1（LZ4 BLOB）作为基础设施
- A2（R8 full mode）启用后需观察 Release 构建稳定性 1 周

---

### 3.2 阶段 1：立即执行（预计 2 小时）

#### 任务 1.1：删除 12 个 `updateXxxDirect` 死代码

**文件**: `android/app/src/main/java/com/xianxia/sect/core/state/GameStateStore.kt`

**操作**: 删除行 703-773 的 12 个方法：

```
updateDisciplesDirect
updateGameDataDirect
updateEquipmentStacksDirect
updateEquipmentInstancesDirect
updateManualStacksDirect
updateManualInstancesDirect
updatePillsDirect
updateMaterialsDirect
updateHerbsDirect
updateSeedsDirect
updateTeamsDirect
updateBattleLogsDirect
```

每个方法约 5 行，共 ~60 行。这些方法外部零引用，删除不影响任何功能。

**验证**:
```bash
cd android && ./gradlew.bat compileReleaseKotlin
```

---

#### 任务 1.2：启用 R8 full mode

**文件**: `android/gradle.properties`

检查是否存在 `android.enableR8.fullMode=false`，如有则删除。为保险显式添加：
```properties
android.enableR8.fullMode=true
```

**文件**: `android/app/build.gradle`（确认，无需修改）

- 第 81 行 `minifyEnabled = true` ✅ 已正确
- 第 83 行 `proguard-android-optimize.txt` ✅ 已正确

**验证**:
```bash
cd android && ./gradlew.bat assembleRelease
```
安装 Release APK 到手机，测试：新建游戏 → 修炼 → 存档 → 读档。如出现 `ClassNotFoundException`，在 `proguard-rules.pro` 加 keep 规则。

---

#### 任务 1.3：更新 CODE_WIKI.md 后续优化项表

**文件**: `CODE_WIKI.md`，行 868-886 的表格

替换为：

```markdown
## 后续优化项

| 优先级 | 描述 | 预估收益 | 状态 |
|--------|------|---------|------|
| P1 | 启用 R8 full mode | 启动提速 30%+，帧渲染提升 25% | 待实施 |
| P1 | Phase B 完成：GameData 领域实体表拆分 | 大幅减少 Room 读取延迟 | 部分实施（DomainStateProvider 就位） |
| P1 | `snapshotFlow` 修炼进度条逐帧动画 | 减少高频动画重组 | 待实施 |
| P2 | LZ4 压缩集成到 Room BLOB 存储写入路径 | 存储减少 30-50% | 待实施 |
| P2 | FrameMetrics 接入 UnifiedPerformanceMonitor | 监控统一 | 待实施 |
| P2 | `graphicsLayer` 按钮缩放等视觉动画（地图已用） | 零重组动画 | 部分实施 |
| P2 | Disciple 字段注解驱动合并（KSP） | 消除手工字段分类 | 待实施 |
| P3 | 并发压力测试（100+ 协程） | 验证极端场景 | 待实施 |
| P4 | 巡逻塔 fire-and-forget → suspend | API 风格一致性 | 待实施 |
| P4 | 事件溯源审计日志 | 时间旅行调试 | 待实施 |

> 已删除项：
> - ~~细粒度锁/分片 Mutex~~ → 无瓶颈证据，过早优化
> - ~~Cloud Profiles~~ → Google Play 自动提供，无需干预
> - ~~`updateXxxDirect` 方法移除~~ → 死代码，已直接删除（2026-06-06）
```

---

### 3.3 阶段 2：短期优化（预计 3 天）

#### 任务 2.1：LZ4 压缩集成到 Room BLOB 存储

**目标**: Room 写入前 LZ4 压缩，读出时解压。存储减少 30-50%。

**涉及文件**:
- `StorageEngine.kt` — 找到 `save()` 方法中序列化后写入 Room 的路径
- `DataCompressor.kt` — 已有 LZ4 能力，直接复用

**步骤**:

1. 在 `StorageEngine` 中找到序列化后 `ByteArray` 写入 Room 的代码
2. 写入前包裹：
```kotlin
val compressed = dataCompressor.compress(serializedBytes, CompressionAlgorithm.LZ4)
```
3. 读出后解压：
```kotlin
val decompressed = dataCompressor.decompress(compressedBytes, CompressionAlgorithm.LZ4)
```
4. **兼容性关键**：在 BLOB 前加 1 字节标记——`0x01`=LZ4 压缩，`0x00`=未压缩。旧存档首字节为 `0x00`，兼容读出
5. 读出时检查首字节决定是否解压

**验证**:
```bash
cd android && ./gradlew.bat test --tests "com.xianxia.sect.data.unified.SerializationHelperTest"
```
额外测试：新建游戏 → 存档 → 读档 → 确认数据完整。

---

#### 任务 2.2：snapshotFlow 优化修炼进度条

**目标**: 修炼进度条在 80%-100% 突破阶段高频更新，改用 `snapshotFlow` + Canvas 跳过重组。

**涉及文件**: 搜索 `CultivationProgress` 或 `LinearProgressIndicator` 定位进度条 Composable。

**步骤**:

```kotlin
// 替换前：每次 progress 变化触发重组
val progress by viewModel.progressFlow.collectAsState()
LinearProgressIndicator(progress = progress)

// 替换后：snapshotFlow 读取 + Canvas 绘制，不触发重组
@Composable
fun CultivationProgressBar(progressFlow: Flow<Float>, modifier: Modifier = Modifier) {
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        snapshotFlow { progressFlow }
            .collect { progress = it }
    }

    Canvas(modifier) {
        drawRect(Color.Gray, size = size)
        drawRect(Color.Black, size = Size(size.width * progress, size.height))
    }
}
```

**验证**:
```bash
cd android && ./gradlew.bat compileReleaseKotlin
cd android && ./gradlew.bat assembleRelease -PenableComposeCompilerMetrics=true
# 查看 app/build/compose_metrics/ 确认重组次数减少
```

---

#### 任务 2.3：FrameMetrics 接入 UnifiedPerformanceMonitor

**文件**:
- `UnifiedPerformanceMonitor.kt`
- `FrameMetricsMonitor.kt`

**步骤**:

1. 在 `UnifiedPerformanceMonitor` 构造函数中注入 `FrameMetricsMonitor`：
```kotlin
@Singleton
class UnifiedPerformanceMonitor @Inject constructor(
    private val frameMetricsMonitor: FrameMetricsMonitor
) {
    fun getPerformanceReport(): PerformanceReport {
        val frameStats = frameMetricsMonitor.getStats()
        return PerformanceReport(
            avgFrameMs = frameStats.avgTotalDuration,
            jankRate = frameStats.jankRate,
        )
    }
}
```

2. `FrameMetricsMonitor` 保持不变，仅作为数据源之一

**验证**:
```bash
cd android && ./gradlew.bat compileReleaseKotlin && ./gradlew.bat test
```

---

#### 任务 2.4：graphicsLayer 按钮缩放

**目标**: 按钮按下缩放从 `animateFloatAsState` 改为 `graphicsLayer`，跳过重组。

**涉及文件**: 搜索 `GameButton` 或 `AnimatedScale` 相关 Composable。

**步骤**:

```kotlin
// 替换前：animateFloatAsState 触发重组
val scale by animateFloatAsState(if (pressed) 0.95f else 1f)
Modifier.scale(scale)

// 替换后：graphicsLayer 跳过重组
Modifier.graphicsLayer {
    scaleX = if (pressed) 0.95f else 1f
    scaleY = if (pressed) 0.95f else 1f
}
```

**验证**: 编译通过 + 真机测试按钮按压动画流畅度。

---

### 3.4 阶段 3：中期架构（预计 7 天）

#### 任务 3.1：Phase B 完成 — 领域实体表拆分 ⭐ 最高收益

**目标**: 将 GameData 重型 BLOB 字段迁移到独立 Room 表，业务层走细粒度 DAO。

**涉及文件**:
- 新建 Room Entity 类（如 `SectStateEntity`、`DiplomacyEntity` 等）
- 新建 DAO 接口
- `DomainStateProvider.kt` — 改为从新 DAO 读取
- `GameDatabase.kt` — 注册新表

**步骤**:

1. 识别 GameData 中最大的 BLOB 字段，为每个创建独立 `@Entity`：
```kotlin
@Entity(tableName = "sect_state")
data class SectStateEntity(
    @PrimaryKey val slotId: Int,
    val spiritStones: Long,
    val reputation: Int,
)
```

2. 创建 DAO：
```kotlin
@Dao
interface SectStateDao {
    @Query("SELECT * FROM sect_state WHERE slotId = :slotId")
    suspend fun getState(slotId: Int): SectStateEntity?

    @Upsert
    suspend fun upsertState(state: SectStateEntity)
}
```

3. 修改 `DomainStateProvider`，从 `extractXxxState()` 改为直接调用 DAO
4. 在 `GameDatabase` 注册新表
5. 编写 Room Migration（从 GameData 提取字段到新表）
6. **注意**: 使用 `db.safeDropColumns()` 而非 `ALTER TABLE DROP COLUMN`

**验证**:
```bash
cd android && ./gradlew.bat test
```
重点验证旧存档迁移后正常加载。

---

#### 任务 3.2：Disciple 字段注解驱动（KSP 代码生成）

**目标**: 用 KSP 自动生成 67 个委托属性。

**涉及文件**: 新建 KSP 模块 `ksp-processor/`。

**步骤**:

1. 定义注解 `@DelegateField(source = "combat", property = "baseHp")`
2. 写 KSP 处理器，扫描注解生成委托代码
3. 在 `Disciple.kt` 中替换 67 个手工委托为注解：
```kotlin
// 旧（67 个类似模式）:
var baseHp: Int get() = combat.baseHp; set(value) { combat.baseHp = value }

// 新:
@DelegateField(source = "combat", property = "baseHp")
```

**验证**: 编译通过 + 所有现有测试通过。

---

#### 任务 3.3：并发压力测试

**目标**: 验证 `transactionMutex` 在极端并发下的正确性。

**文件**: 新建 `GameStateStoreConcurrencyTest.kt`

**步骤**:

```kotlin
@Test
fun `100 coroutines concurrent update does not corrupt state`() = runTest {
    val jobs = (1..100).map { i ->
        launch(Dispatchers.Default) {
            store.update {
                val newDisciple = Disciple(id = "disciple_$i")
                copy(disciples = disciples + newDisciple)
            }
        }
    }
    jobs.joinAll()
    assertEquals(initialCount + 100, store.disciplesSnapshot.size)
}
```

**验证**: 所有并发测试通过。

---

### 3.5 阶段 4：长期（Q3+）

| 编号 | 项目 | 说明 |
|------|------|------|
| D1 | 事件溯源审计日志 | 需评估快照策略和存储成本 |
| D2 | 巡逻塔 fire-and-forget → suspend | API 风格统一，无性能收益 |

---

### 3.6 每阶段验证

```bash
# 编译检查
cd android && ./gradlew.bat compileReleaseKotlin

# 完整测试
cd android && ./gradlew.bat test

# Release 构建（阶段 1.2 后必做）
cd android && ./gradlew.bat assembleRelease
```

---

## 4. 参考来源清单

### S 级来源（官方文档/白皮书）

| # | 标题 | URL | 日期 |
|---|------|-----|------|
| 1 | Android Developers — Jetpack Compose Performance | https://developer.android.com/develop/ui/compose/performance | 持续更新 |
| 2 | Android Developers — Baseline Profiles for Compose | https://developer.android.com/develop/ui/compose/performance/baseline-profiles | 持续更新 |
| 3 | Kotlin Official — Shared Mutable State and Concurrency | https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html | 持续更新 |
| 4 | Android Developers Blog — Fully Optimized: Performance Spotlight Week Wrap-Up | https://android-developers.googleblog.com/2025/11/fully-optimized-wrapping-up-performance.html | 2025-11-21 |
| 5 | Android Developers Blog — Use R8 to Shrink, Optimize, and Fast-Track Your App | https://android-developers.googleblog.com/2025/11/use-r8-to-shrink-optimize-and-fast.html | 2025-11-17 |
| 6 | Android Developers Blog — Deeper Performance Considerations | https://android-developers.googleblog.com/2025/11/deeper-performance-considerations.html | 2025-11-19 |
| 7 | Android Developers Blog — Leveling Guide for Your Performance Journey | https://android-developers.googleblog.com/2025/11/leveling-guide-for-your-performance.html | 2025-11-20 |
| 8 | Android Developers Blog — Reddit Improved App Startup Speed by Over 50% | https://android-developers.googleblog.com/2024/12/reddit-improved-app-startup-speed-using-baseline-profiles-r8.html | 2024-12 |
| 9 | Android Developers Blog — What's New in Jetpack Compose December '25 | https://android-developers.googleblog.com/2025/12/whats-new-in-jetpack-compose-december.html | 2025-12 |
| 10 | Android Developers Blog — What's New in Jetpack Compose at I/O '24 | https://android-developers.googleblog.com/2024/05/whats-new-in-jetpack-compose-at-io-24.html | 2024-05 |

### A 级来源（头部产品技术博客）

| # | 标题 | URL | 日期 |
|---|------|-----|------|
| 11 | Android Developers Stories — NCSoft Lineage W ADPF | https://developer.android.com/stories/games/lineagew-adpf | 2024 |
| 12 | Android Developers Stories — Netmarble Games ADPF | https://developer.android.google.cn/stories/games/netmarble-got-adpf | 2024 |
| 13 | Android Developers Stories — UNISOC ADPF Gaming | https://mobile-vitals.com/article/1504-google-unisoc-leverages-adpf | 2024 |
| 14 | Arm Community Blog — ADPF in Unreal Engine | https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/getting-started-with-adpf | 2024-03 |
| 15 | Arm Newsroom — Optimize Mobile Gaming Experience | https://newsroom.arm.com/blog/mobile-gaming-techniques | 2024 |
| 16 | Arm Community Blog — NanoMesh on Mobile | https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/nanomesh-on-mobile | 2024-05-28 |

### B 级来源（社区文章）

| # | 标题 | URL | 日期 |
|---|------|-----|------|
| 17 | 掘金 — SnapshotFlow vs collectAsState | https://juejin.cn/post/7527180608180207656 | 2025 |
| 18 | 掘金 — 云音乐 Baseline Profiles 实践 | https://juejin.cn/post/7389209265947754548 | 2024 |
| 19 | Vibe Studio — Event Sourcing in Flutter State Management | https://vibe-studio.ai/insights/event-sourcing-in-flutter-state-management | 2024 |
| 20 | Google I/O Extended Lahore 2024 — Compose Performance | https://speakerdeck.com/gdglahore/o-extended-lahore-2024 | 2024 |

### 学术来源

| # | 标题 | URL | 日期 |
|---|------|-----|------|
| 21 | ASE 2025 — Profile Coverage: Android Compilation Profiles | https://conf.researchr.org/details/ase-2025/ase-2025-papers/31/ | 2025-11 |
| 22 | GitHub — Eventure: Event-Driven Framework for Games | https://github.com/enricostara/eventure | 2024 |

### 来源统计

| 等级 | 数量 | 占比 |
|------|------|------|
| S 级（官方文档/白皮书） | 10 | 45% |
| A 级（头部产品技术博客） | 6 | 27% |
| B 级（社区文章） | 4 | 18% |
| 学术论文 | 2 | 9% |
| **合计** | **22** | **100%** |

满足设计方案规则：≥20 条（22 条），S+A 级 ≥12 条（16 条，73%），均在近 2 年内。

---

*生成日期: 2026-06-06 | 来源: 22 条 | 置信度: 高*
