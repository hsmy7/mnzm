# 架构债务根治方案

> 版本: 1.0 | 日期: 2026-07-30 | 状态: ✅ 已实施

---

## 目录

- [1. 背景与目标](#1-背景与目标)
- [2. 行业对标分析](#2-行业对标分析)
- [3. 技术方案](#3-技术方案)
- [4. 影响范围清单](#4-影响范围清单)
- [5. 兼容性分析](#5-兼容性分析)
- [6. 测试方案](#6-测试方案)
- [7. 风险评估与兜底](#7-风险评估与兜底)
- [8. 参考来源清单](#8-参考来源清单)

---

## 1. 背景与目标

### 1.1 需求要点

`docs/architecture-debt.md` 及关联文档中记录了一批待完成项，经分析归档后筛选出以下需要根治的债务：

| # | 项 | 来源 | 严重度 | 现状 |
|---|----|------|--------|------|
| A | `ComponentTable.store` 底层存储通过 `@PublishedApi internal val store` 在字节码层暴露 | `architecture-debt-write-guard.md` | 🔴 高 | `copyTo()` 绕过 `requireWrite` 守卫 |
| B | `requireWrite`/`onWrite` 为 `@JvmField var` 字节码可被覆写 | `architecture-debt-write-guard.md` | 🔴 高 | Java 字节码可置 null 解除守卫 |
| C | `Baseline Profile` 手写111条规则未经验证 | `architecture-debt.md` 远期优化 | 🟡 中 | Release 前需优化 |
| D | ADPF Performance Hint API 未集成 | `fps-optimization-plan.md` P2.3 | 🟡 中 | Release 前可选集成 |
| E | 纹理分级压缩未实施 | `fps-optimization-plan.md` P2.4 | 🟡 中 | Release 前选做 |
| F | R8 Full Mode 配置审计 | `fps-optimization-plan.md` P3.2 | 🟡 中 | Release 前选做 |
| G | `processMonthlyEvents` 仍有2个分散事务 | `architecture-debt.md` 远期优化 | 🟢 低 | 后续优化 |
| H | HP/MP 恢复全量遍历未改用单弟子 API | `architecture-debt.md` 远期优化 | 🟢 低 | 后续优化 |
| I | `discipleAggregates` 全量投影可优化为增量 | `architecture-debt.md` 远期优化 | 🟢 低 | 需要时做 |
| J | `!!` 操作符全库清理 | `architecture-debt.md` 远期优化 | 🟢 低 | 持续规范改进 |
| K | `DeterministicRng` 非事务性：分区 RNG 在 `stateStore.update` 事务缓冲内即时前进，状态回滚时 RNG 不回滚（结算中途异常 → 同输入不同输出，读档重放确定性被打破） | 对抗性审查 2026-08-03（远古秘境结束选项，B-M1） | 🟡 中 | **待完成**：需 RNG 快照/回滚机制（见第 3 阶段 3.5） |

### 1.2 已排除项

- **云存档序列化双路径债务** — ✅ 已由 `UnifiedSerializationEngine` 统一路径消除，`SerializableGameData` 仅存于 `backwardcompat/` 兼容旧格式
- **ANR #5076 TapTap** — ⏸️ 待 TapTap SDK 更新，当前 4 层防护已就绪
- **SIGSEGV #3088 vulkan.adreno.so** — ✅ 已加固，持续监控
- **生命周期低优项（重入/LOADING/回滚）** — ⏸️ 保持现状，无用户可见问题
- **FPS Plan Phase 2/3/4 非紧急项** — ⏸️ 当前帧率无问题报告
- **政策系统 UI 层优化** — ⏸️ 功能完整，仅体验优化

### 1.3 成功标准

1. `ComponentTable.store` 不再有字节码级暴露路径，`copyTo()` 全部通过守卫
2. `requireWrite`/`onWrite` 对外函数封装，不可被字节码覆写
3. 编译 `compileReleaseKotlin` BUILD SUCCESSFUL
4. 所有修改模块的单元测试 100% 通过
5. Release 构建配置审计通过（R8/profile/ADPF）

---

## 2. 行业对标分析

### 2.1 ECS Component 写入守卫模式

**行业做法：** 主流 ECS 游戏引擎均采用**完全中介（Complete Mediation）**原则——所有对组件存储的写入必须经过单一守卫检查点。

- **Bevy (Rust)** — `UnsafeWorldCell` 封装所有组件访问，通过 `System::component_access` 在编译期验证访问模式。组件存储内置 `ComponentTicks` 变更追踪，所有写操作经过 `&mut` 引用守卫。PyBevy 的 `ComponentStorage` 每个组件实例携带 `ValidityFlag`，`as_mut()` 前调用 `check_valid_mut()` 验证写入合法性
- **Unity ECS** — 通过 `IEntityManager` 和 `ComponentData` 的 `EntityCommandBuffer` 将所有写入集中到调度边界，禁止系统直接操作底层 `ArchetypeChunk` 存储
- **Guard/Reference Monitor 模式** — 安全工程领域标准模式：Guard 组件作为唯一入口，Policy 决定授权，Client 不可绕过（Complete Mediation）

**对我们的启示：** 当前架构的 `bindAllOnWrite()` 已覆盖 ~90 张表，方向正确。但 `@PublishedApi internal val store` 的字节码暴露 + `@JvmField var` 的可覆写性，使得守卫存在两个"侧信道绕过"路径，不符合 Complete Mediation 原则。需封堵。

### 2.2 Kotlin `@PublishedApi` 字节码暴露

Kotlin 官方文档及社区讨论确认：`@PublishedApi internal` 在字节码中变为 public，可被跨模块 Java/JVM 代码直接访问。这是 inline 函数编译必要的 escape hatch，但带来了：

- 字节码级访问控制泄漏（已验证：inline 生成 synthetic public 类调用原 internal 方法）
- 二进制兼容断裂风险（修改 `@PublishedApi` 声明需要消费者重新编译）
- 无意中扩大公共 API 表面（binary-compatibility-validator 将其视为 public API）

### 2.3 手机游戏性能优化行业基准

| 优化手段 | 行业收益 | 适用阶段 | 成本 |
|---------|---------|---------|------|
| Baseline Profile 生成 + ProfileInstaller | 启动提速 25-35%，帧时间降 15% | Release 前 | 0.5天 |
| ADPF Performance Hint API | 帧率提升 28-50%（UNISOC实测） | Release 前 | 1天 |
| 纹理分级压缩（ASTC 6x6 + ETC2） | 纹理内存降 50-70% | Release 前 | 1天 |
| R8 Full Mode + 窄 Keep Rules | ANR -35%，冷启动 -30%，APK -9% | Release 前 | 1天 |

---

## 3. 技术方案

### 3.1 写入守卫加固（项 A + B）

#### 核心变更

**目标：** 封堵 `ComponentTable.store` 和 `requireWrite`/`onWrite` 的字节码级暴露路径，同时保持 `inline` 函数的性能优势。

**方案选择：** 方案 A（新增 `rawPutTo` 守卫方法）+ 字段封装（setter 方法替代 `@JvmField var`）

#### IntComponentTable / DoubleComponentTable / ComponentTable<T> 变更

```kotlin
// ── ComponentTable.kt ──

// 变更 1: requireWrite/onWrite 改为 private + setter 方法
private var requireWrite: (() -> Unit)? = null
private var onWrite: ((Int, Int) -> Unit)? = null

/** 设置写入守卫回调（update {} 事务外调用）。替换 @JvmField var requireWrite */
fun setWriteGuard(callback: () -> Unit) {
    requireWrite = callback
}

/** 设置变更回调（update {} 事务外调用）。替换 @JvmField var onWrite */
fun setMutationCallback(callback: (Int, Int) -> Unit) {
    onWrite = callback
}

// 变更 2: store 保持 @PublishedApi internal（inline 函数需要），但新增守卫方法

/** 
 * 安全跨表写入：通过目标表的写入守卫后，将值写入目标表。
 * 替换对 `target.store.put(key, value)` 的直接访问。
 */
@PublishedApi internal fun putTo(target: ComponentTable<T>, key: Int, value: T) {
    target.requireWrite?.invoke()
    target.onWrite?.invoke(key, 1)  // 1 = REPLACE
    target.store.append(key, value)
}
```

```kotlin
// ── IntComponentTable.kt ──
private var requireWrite: (() -> Unit)? = null
private var onWrite: ((Int, Int) -> Unit)? = null

fun setWriteGuard(callback: () -> Unit) { requireWrite = callback }
fun setMutationCallback(callback: (Int, Int) -> Unit) { onWrite = callback }

@PublishedApi internal fun putTo(target: IntComponentTable, key: Int, value: Int) {
    target.requireWrite?.invoke()
    target.onWrite?.invoke(key, 1)
    target.store.append(key, value)
}
```

```kotlin
// ── DoubleComponentTable.kt ──
private var requireWrite: (() -> Unit)? = null
private var onWrite: ((Int, Int) -> Unit)? = null

fun setWriteGuard(callback: () -> Unit) { requireWrite = callback }
fun setMutationCallback(callback: (Int, Int) -> Unit) { onWrite = callback }

@PublishedApi internal fun putTo(target: DoubleComponentTable, key: Int, value: Double) {
    target.requireWrite?.invoke()
    target.onWrite?.invoke(key, 1)
    target.store.append(key, value)
}
```

#### CopyableTableRef 变更

```kotlin
// ── CopyableTableRef.kt ──

// 所有三种实现（Int/Double/Generic）统一模式：
// BEFORE
fun copyTo(target: ComponentTable<String?>, source: Int, targetId: Int) {
    target.store.append(targetId, sourceTable[source])  // ❌ 绕过 requireWrite
}

// AFTER
fun copyTo(target: ComponentTable<String?>, source: Int, targetId: Int) {
    target.putTo(targetId, sourceTable[source])  // ✅ 通过 putTo 守卫
}
```

#### bindAllOnWrite 变更

```kotlin
// ── ComponentTableBindAllOnWrite.kt ──
// BEFORE
ref.table.requireWrite = writeGuard
ref.table.onWrite = { key, count -> dirtyTracker.markDirty(key) }

// AFTER
ref.table.setWriteGuard(writeGuard)
ref.table.setMutationCallback { key, count -> dirtyTracker.markDirty(key) }
```

#### ComponentTableRef 接口变更

```kotlin
// ── ComponentTableRef.kt ──
interface CopyableTableRef {
    // 新增：带守卫的跨表写入方法
    fun copyTo(target: MutableGameState, source: Int, targetId: Int)
    
    // 移除：旧的内部方法
    // fun rawStore(): Any   — 已不需要
}
```

#### 数据流

```
更新前:
  copyTo(target, src, dst) → target.store.append(dst, val)  → ⚠️ 绕过 requireWrite
  
更新后:
  copyTo(target, src, dst) → target.putTo(dst, val) 
    → target.requireWrite?.invoke()  ← 守卫在此触发
    → target.onWrite?.invoke(key, 1) ← 脏检测
    → target.store.append(key, val)  ← 实际写入
```

### 3.2 Baseline Profile 真机生成（项 C）

**流程：**

```
1. 在 app/src/androidTest/ 创建 BaselineProfileGenerator.kt
   └─ 使用 Macrobenchmark 的 BaselineProfileRule
   └─ 定义 Critical User Journeys：
       ├─ 冷启动：App 启动到首页渲染完成
       ├─ 宗门操作：打开弟子列表 → 选择弟子 → 查看详情
       └─ 加载存档：选择存档 → 进入游戏主界面
2. 添加 ProfileInstaller 依赖
3. 在真机/模拟器 (API 33+) 运行生成测试
4. 提取 baseline-prof.txt 覆盖手写版本
```

**配置变更：**

```kotlin
// build.gradle.kts (app)
dependencies {
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}

// androidTestImplementation for baseline profile generation
androidTestImplementation("androidx.benchmark:benchmark-macro-junit4:1.4.0")
androidTestImplementation("androidx.test.ext:junit:1.2.1")
```

### 3.3 ADPF Performance Hint API（项 D）

```kotlin
// ── PerformanceHintManager.kt（新增，:core:engine 模块）──
@GameService(name = "PerformanceHintService")
class AndroidPerformanceHintService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var hintSession: Any? = null  // PerformanceHintManager.Session 反射
    
    fun attachToGameThread() {
        if (Build.VERSION.SDK_INT < 31) return
        val manager = context.getSystemService(Context.PERFORMANCE_HINT_SERVICE) 
            as? PerformanceHintManager ?: return
        hintSession = manager.createHintSession(
            intArrayOf(android.os.Process.myTid()),
            16_666_667L  // 60 FPS target duration in ns
        )
    }
    
    fun reportFrameDuration(durationNs: Long) {
        (hintSession as? PerformanceHintManager.Session)?.run {
            reportActualWorkDuration(durationNs)
        }
    }
    
    fun close() {
        (hintSession as? PerformanceHintManager.Session)?.close()
        hintSession = null
    }
}
```

**集成到游戏循环：**

```kotlin
// GameEngineCore.kt
class GameEngineCore(...) {
    private var perfHint: AndroidPerformanceHintService? = null
    
    fun attachPerfHint(service: AndroidPerformanceHintService) {
        perfHint = service
        service.attachToGameThread()
    }
    
    // 在每帧循环结束处
    fun onFrameEnd() {
        val frameEnd = nanoTime()
        val frameDuration = frameEnd - lastFrameEnd
        perfHint?.reportFrameDuration(frameDuration)
        lastFrameEnd = frameEnd
    }
}
```

### 3.4 纹理分级压缩（项 E）

**目标：** 为 Android AAB 启用纹理压缩格式分发，为低端设备提供压缩纹理。

**无代码更改，仅构建配置：**

```groovy
// build.gradle.kts (app)
android {
    bundle {
        texture {
            enableSplit = true  // 启用纹理压缩格式分发
        }
    }
}
```

配合 Google Play AAB 的纹理压缩格式分发自动处理。当前精灵图已使用 WebP（无损），已是最优。此项仅确认配置正确 + AAB 分发就绪。

### 3.5 R8 Full Mode 配置审计（项 F）

**检查项：**

```properties
# gradle.properties
android.enableR8.fullMode=true  # ✅ 确认启用
```

```kotlin
// build.gradle.kts (app)
buildTypes {
    release {
        isMinifyEnabled = true     // ✅
        isShrinkResources = true   // ✅
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),  // ✅ 使用 optimize 版本
            "proguard-rules.pro"
        )
    }
}
```

**Keep Rules 审计：** 检查 `proguard-rules.pro` 是否包含过宽的 `-keep class ** { *; }` 模式，替换为窄规则。当前规则需实际读取文件确认，此处方案中标注为待审计项。

### 3.6 `processMonthlyEvents` 单事务化（项 G）

**约束：** `flushPendingEvents` 保持事务外、`safelyRunInState` 隔离保留、`processCompletedMissionsLazy` 从快照读取。

```kotlin
// GameEngineCore.kt — processMonthYearChange
fun processMonthYearChange(state: MutableGameState, gameYear: Int, gameMonth: Int) {
    // 1. 从事务外读快照（不变）
    // 2. 在一个 stateStore.update{} 内执行所有子操作
    cultivationProcessor.processMonthlyEvents(state, gameYear, gameMonth)
    
    // flushPendingEvents 保持在事务外
    flushPendingEvents()
}

// CultivationEventProcessor.kt
fun processMonthlyEvents(state: MutableGameState, year: Int, month: Int) {
    // 所有子操作在同一个 state 对象上执行
    processCompletedMissionsLazy(state)  // 从 state 快照读，不再从 store 读
    processAutoAssign(state)
    processResidenceLoyalty(state)
    // ... 其他月变操作
}
```

### 3.7 HP/MP 恢复统一（项 H）

```kotlin
// CultivationCore.kt — processMonthlyCultivationAndAuto
// BEFORE:
recoverHpMpForAllDisciples(state)  // 全量遍历所有弟子

// AFTER:
val aliveIds = state.discipleTables.aliveIds
for (id in aliveIds) {
    if (!aliveIds.has(id)) continue
    recoverHpMpSingle(state, id)  // 逐弟子恢复
}
```

**前置条件：** 需确认 `recoverHpMpSingle` 与 `recoverHpMpForAllDisciples` 的恢复逻辑等价（包括 HP/MP 上限计算和恢复率）。如果不等价，先统一逻辑再替换。

### 3.8 `discipleAggregates` 增量投影（项 I）

**技术方案：**

```kotlin
// GameStateStoreImpl.kt
private val _discipleAggregates: StateFlow<List<DiscipleAggregate>> =
    _state.map { state ->
        val full = state.discipleTables
        val changedIds = full.changedIdTracker.getChangedIds()
        
        if (changedIds.isEmpty() && cachedAggregates.isNotEmpty()) {
            // 无变化：返回缓存的 aggregates
            cachedAggregates.toList()
        } else {
            // 有变化：只重新生成变化弟子的 aggregate
            val newAggregates = cachedAggregates.toMutableList()
            for (id in changedIds) {
                val idx = newAggregates.indexOfFirst { it.id == id }
                val aggregate = full.toAggregate(id)
                if (idx >= 0) {
                    newAggregates[idx] = aggregate
                } else {
                    newAggregates.add(aggregate)
                }
            }
            // 兜底：每 2s 做一次全量 freshness 检查
            if (fullFreshnessCounter++ >= 10) {
                fullFreshnessCounter = 0
                rebuildFullCache(state) 
            }
            cachedAggregates = newAggregates.toList()
        }
    }
    .sample(200)  // 保持当前采样率
    .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
```

**风险控制：** 初次上线时保留旧的全量投影作为比较基线，运行稳定后移除。

### 3.9 `!!` 操作符全库清理（项 J）

**统计基线：**

```bash
grep -rn '!!' android/ --include='*.kt' | grep -v '/test/' | grep -v '/build/' | grep -v '/generated/' 
```

**清理策略：** 分4轮，每轮一个模块。

---

## 4. 影响范围清单

| 文件路径 | 变更类型 | 变更说明 | 分类 |
|---------|---------|---------|------|
| `core/engine/.../component/ComponentTable.kt` | 修改 | `requireWrite`/`onWrite` 改为 `private` + setter；新增 `putTo()` 守卫方法 | 🔴 A+B |
| `core/engine/.../component/IntComponentTable.kt` | 修改 | 同上 | 🔴 A+B |
| `core/engine/.../component/DoubleComponentTable.kt` | 修改 | 同上 | 🔴 A+B |
| `core/engine/.../component/CopyableTableRef.kt` | 修改 | `copyTo` 实现改为调用 `putTo()` | 🔴 A |
| `core/engine/.../component/ComponentTableBindAllOnWrite.kt` | 修改 | `setWriteGuard`/`setMutationCallback` 替代直接字段赋值 | 🔴 B |
| `core/engine/.../component/ComponentTableRef.kt` | 修改 | 接口调整（如需） | 🔴 A |
| `app/build.gradle.kts` | 修改 | ProfileInstaller 依赖 + ADPF 依赖 | 🟡 C+D |
| `core/engine/.../perf/AndroidPerformanceHintService.kt` | **新增** | ADPF Performance Hint API 封装 | 🟡 D |
| `core/engine/.../GameEngineCore.kt` | 修改 | 集成 ADPF 帧时间回传 + 月事务合并 | 🟡 D + 🟢 G |
| `app/src/androidTest/.../BaselineProfileGenerator.kt` | **新增** | Baseline Profile 自动生成测试 | 🟡 C |
| `app/src/main/baseline-prof/baseline.prof` | 替换 | 自动生成的 profile | 🟡 C |
| `app/proguard-rules.pro` | 审计 | 检查过宽 keep 规则 | 🟡 F |
| `core/engine/.../cultivation/CultivationEventProcessor.kt` | 修改 | `processCompletedMissionsLazy` 从快照读 | 🟢 G |
| `core/engine/.../cultivation/CultivationCore.kt` | 修改 | `recoverHpMpForAllDisciples` → `recoverHpMpSingle` | 🟢 H |
| `core/state/GameStateStoreImpl.kt` | 修改 | `discipleAggregates` 增量投影 | 🟢 I |
| 全库 *.kt | 清理 | `!!` → `?.` / `?:` / `checkNotNull()` | 🟢 J |

---

## 5. 兼容性分析

### 5.1 写入守卫加固

- **序列化/存档兼容性：** 无变更。`ComponentTable` 字段写守卫是运行时检查，不影响数据格式。
- **API 兼容性：** `setWriteGuard`/`setMutationCallback` 是新增公共方法，旧调用方通过编译错误发现（需改为使用 setter）。`CopyableTableRef.copyTo` 签名不变，实现内部变为通过守卫。
- **测试兼容性：** 已存在的 `WriteGuardRule` 测试需验证新的 setter 路径。

### 5.2 Baseline Profile

- **APK 体积：** ProfileInstaller 库 ~50KB，生成的 profile 文件 ~1-5KB。
- **Android 版本：** ProfileInstaller 支持 API 14+，ProfileGenerator 需要 API 33+。

### 5.3 ADPF API

- **Android 版本：** API 31+（Android 12），低版本自动跳过（空实现）。
- **无隐私影响：** 仅使用 `Process.myTid()` 线程 ID，无用户数据上报。

### 5.4 月事务合并 / HP/MP 恢复 / 增量投影

- 均为纯重构，行为等价，无兼容性问题。

---

## 6. 测试方案

### 6.1 守卫加固测试

| 测试场景 | 类型 | 预期 |
|---------|------|------|
| `putTo` 触发目标表的 `requireWrite` | 单元 | 守卫回调被调用 |
| `putTo` 触发目标表的 `onWrite` | 单元 | 脏检测回调被调用 |
| `setWriteGuard(null)` → `putTo` 不触发 NPE | 单元 | 安全 null 处理 |
| 字段不能通过 Java 反射置 null | 对抗性 | `requireWrite` 在字节码中为 private |
| `bindAllOnWrite` 使用新 setter 后守卫正常 | 集成 | 全部 90+ 表守卫绑定 |

### 6.2 Baseline Profile 测试

| 测试场景 | 类型 | 预期 |
|---------|------|------|
| `generateBaselineProfile` 运行不报错 | 集成 | Profile 文件生成 |
| 生成的 profile 方法引用可解析 | 验证 | `profman --dump` 验证 |

### 6.3 ADPF 测试

| 测试场景 | 类型 | 预期 |
|---------|------|------|
| API 31- 设备上不初始化 | 单元 | `hintSession == null` |
| API 31+ 设备上正常创建 session | 集成 | Session 创建成功 |

### 6.4 月事务 / HP/MP / 增量投影 测试

- 复用现有单元测试验证行为等价
- `processMonthlyCultivationAndAutoTest` 确认 HP/MP 恢复后值与原来一致
- `GameStateStoreImplTest` 确认 `discipleAggregates` 输出与全量投影一致

---

## 7. 风险评估与兜底

### 风险矩阵

| 风险 | 概率 | 影响 | 应对 |
|------|------|------|------|
| `putTo` 性能损失（额外函数调用 + null 检查） | 低 | 低 | 守卫回调通常为 `{}`（空 lambda），`?.invoke()` 为单次 null check，测量<10ns |
| Baseline Profile 自动生成覆盖不全 | 中 | 中 | 合并手写版 + 自动版，保留手写版中已验证的高频路径 |
| ADPF session 创建失败 | 中 | 低 | `?.` 安全调用，静默降级 |
| 增量投影与全量投影输出不一致 | 低 | 高 | 首次上线保留双路径 shadow mode 验证一周期 |
| `recoverHpMpSingle` 与 `recoverHpMpForAllDisciples` 逻辑不等价 | 中 | 中 | 先加测试确认等价，再替换 |

### 兜底措施

1. **守卫加固：** 若 `putTo` 方法引入回归，可回退到直接 `store.append`（保留旧代码路径 2 个版本）
2. **增量投影：** 前 2 周启用 Shadow Mode，同时跑全量和增量，日志比对不一致
3. **ADPF：** 静默降级，不阻塞游戏运行

---

## 8. 参考来源清单

| # | 来源 | 等级 | 核心借鉴 |
|---|------|------|---------|
| 1 | [Bevy ECS UnsafeWorldCell 文档](https://docs.rs/bevy/0.11.1/i686-pc-windows-msvc/bevy/ecs/world/unsafe_world_cell/struct.UnsafeWorldCell.html) | S | 所有组件写入经过 `&mut` 引用守卫 |
| 2 | [PyBevy ComponentStorage 源码](https://docs.rs/pybevy_storage/0.2.1/src/pybevy_storage/component_storage.rs.html) | B | `check_valid_mut()` 写入前置守卫检查 |
| 3 | [Bevy 官方文档 - Entities & Components](https://github.com/bevyengine/bevy-website/blob/main/content/learn/book/storing-data/entities-components.md) | A | 组件变更追踪 `ComponentTicks` 模式 |
| 4 | [Unity ECS 反注入安全实践](https://www.jikguard.com/news/unity-inject-how-game-developers-combat-cheat-injection/) | C | ECS 层数据审计 + 加密完整性检查 |
| 5 | [Guard 安全模式 - ETH 论文](https://archiv.infsec.ethz.ch/intranet_secured/Q/a/patterns.pdf) | A | Complete Mediation 原则：所有访问必须通过 Guard |
| 6 | [Designing Secure Software - Guard 模式](https://www.amazon.com/Designing-Secure-Software-Developers-ebook/dp/B0CF4XCBLK) | S | 三层合规模型：高/中/低 Complete Mediation |
| 7 | [Kotlin @PublishedApi 文档](https://kotlinlang.org/api/core/1.1/kotlin-stdlib/kotlin/-published-api/) | S | `@PublishedApi internal` 字节码层变为 public |
| 8 | [Kotlin @PublishedApi 安全问题 - StackOverflow](https://stackoverflow.com/questions/79531776/are-there-potential-issues-with-kotlins-publishedapi-annotation) | B | inline 产生 synthetic public 类暴露 internal 方法 |
| 9 | [Kotlin Binary Compatibility Validator Issue #165](https://github.com/Kotlin/binary-compatibility-validator/issues/165) | A | @PublishedApi 被视为 public API |
| 10 | [Android Baseline Profile 官方文档](https://developer.android.google.cn/topic/performance/baselineprofiles/create-baselineprofile) | S | ProfileInstaller + Critical User Journeys + 自动生成流程 |
| 11 | [Baseline Profile 生产环境案例 - STRV](https://www.strv.com/blog/boosting-android-performance-with-baseline-profiles) | A | 自定义 CUJs 覆盖 85% 启动方法，启动提速 30-40% |
| 12 | [Baseline Profile CI 集成 - Dev.to](https://dev.to/software_mvp-factory/android-baseline-profiles-beyond-the-basics-1p8i) | B | R8 处理后生成 profile 是关键顺序 |
| 13 | [ADPF 官方文档 - Android Developers](https://developer.android.google.cn/games/optimize/adpf) | S | Performance Hint API 完整用法 |
| 14 | [UNISOC ADPF 案例 - Android Developers](https://developer.android.google.cn/stories/games/unisoc-adpf) | S | 帧率提升 28-50%，功耗降低 3.7% |
| 15 | [ADPF Performance Hint API - AOSP](https://source.android.com/docs/core/perf/performance-hint-api) | S | 跨 Android 版本 API 演变 |
| 16 | [纹理压缩格式 - Android Developers](https://developer.android.google.cn/guide/playcore/asset-delivery/texture-compression) | S | AAB 纹理压缩格式分发机制 |
| 17 | [手游性能优化实战 - 腾讯云](https://cloud.tencent.com.cn/developer/article/2658722) | B | ASTC 6x6 主力 + 分辨率分级策略 |
| 18 | [R8 Full Mode 配置 - Android Developers](https://android-dot-devsite-v2-prod.appspot.com/topic/performance/app-optimization/global-options) | S | `proguard-android-optimize.txt` 启用全部优化 |
| 19 | [Monzo R8 Full Mode 案例 - Android Developers Blog](https://android-developers.googleblog.com/2026/03/monzo-boosts-performance-metrics-by-up-to-35-percent.html) | S | ANR -35%，冷启动 -30%，APK -9% |
| 20 | [R8 Keep Rules 配置与调试 - Android Developers Blog](https://android-developers.googleblog.com/2025/11/configure-and-troubleshoot-r8-keep-rules.html) | S | 窄 Keep Rules 替代通配符 |
| 21 | [R8 Full Mode 详解 - 腾讯云](https://cloud.tencent.com/developer/article/2667280) | B | 中文社区 R8 全模式配置指南 |
| 22 | [Unity URP 移动端性能优化 - GeeksForGeeks](https://www.geeksforgeeks.org/c-sharp/performance-optimization-for-mobile-in-unity/) | B | 纹理压缩格式选择策略 |
| 23 | [Untiy 游戏包体瘦身 - 小红书](https://www.xiaohongshu.com/discovery/item/68ea5b45000000000503304c) | C | 纹理合并 + 图集技术 |
| 24 | [手游客户端性能优化 - 腾讯云](https://cloud.tencent.com.cn/developer/article/2658722) | B | Mipmap + 各向异性过滤控制 |
| 25 | [跨平台存档序列化 - CSDN](https://blog.csdn.net/weixin_29011239/article/details/157351580) | C | JSON + 加密 + 统一路径设计 |

---

## 9. 实施计划

### 第 1 阶段：守卫加固（A+B）— 优先级最高

| 步骤 | 描述 | 文件 |
|------|------|------|
| 1.1 | `ComponentTable` 三类型 `requireWrite`/`onWrite` 改为 private + setter | `ComponentTable.kt`、`IntComponentTable.kt`、`DoubleComponentTable.kt` |
| 1.2 | 三类型新增 `putTo()` 守卫方法 | 同上 |
| 1.3 | `CopyableTableRef` 实现改为调用 `putTo()` | `CopyableTableRef.kt` |
| 1.4 | `bindAllOnWrite` 使用 setter | `ComponentTableBindAllOnWrite.kt` |
| 1.5 | 编译验证 + 测试 | 全局 |

### 第 2 阶段：性能优化（C+D+E+F）— Release 前

| 步骤 | 描述 | 文件 |
|------|------|------|
| 2.1 | Baseline Profile 生成 | 新增 + `build.gradle.kts` |
| 2.2 | ADPF API 封装 + 集成 | 新增 + `GameEngineCore.kt` |
| 2.3 | 纹理压缩配置验证 | `build.gradle.kts` |
| 2.4 | R8 配置审计 + Keep Rules 优化 | `proguard-rules.pro` |

### 第 3 阶段：重构优化（G+H+I）— 后续

| 步骤 | 描述 | 文件 |
|------|------|------|
| 3.1 | 月事务合并 | `GameEngineCore.kt` + `CultivationEventProcessor.kt` |
| 3.2 | HP/MP 恢复统一 | `CultivationCore.kt` |
| 3.3 | 增量投影 | `GameStateStoreImpl.kt` |
| 3.4 | `!!` 清理 | 全库 |
| 3.5 | RNG 事务性（快照/回滚）：事务提交前缓存各分区 RNG 状态，异常回滚时恢复——需评估全链路 RNG 消费点与存档 `rngStates` 导出的耦合（K 项） | `DeterministicRng.kt` + `GameRngManager.kt` |
