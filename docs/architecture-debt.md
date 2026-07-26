# 架构债务记录

> 本文件记录已知的架构债务和待完成的技术改进项。

## 引擎 suspend API 线程安全自动化

**状态：** ⏸️ 基础设施已完成，方法本体改造未全量落地。

### 已完成基础设施

`EngineContextDispatcher` 接口 + `withEngineContext` 已提取为 `core/engine/EngineContextDispatcher.kt`，
`GameEngineCore` 实现，`GameEngine.engineContextDispatcher` 注入。测试用 `FakeEngineContextDispatcher` 绕过 mockito suspend 限制。

### 当前问题

`GameStateStoreImpl.update()` (598-610 行) 包含主线程守卫，Release 构建中检测到主线程调用时**静默跳过更新**。
当前已知违规已全部手工修复（`launchOnEngine` 或 `withContext(Default)`），但以下引擎 `suspend` 方法
直接调用 `stateStore.update{}` 而未在内部切换到引擎线程——调用方必须手工确保在引擎线程上调用，
这是一个容易遗漏的隐式契约，新代码仍可能引入同类违规。

### 待改造方法

- `GameEngineCoordination.kt` — `updateGameData`, `updateDisciple`, `updateGameDataAndSync`, `enterSect`, `changeDiscipleTypeAtomic`, `forgetManual`, `replaceManual`, `learnManual`, `recruitAllFromList`, `startBloodRefinementAtomic`, `cancelBloodRefinement`, `processBloodRefinementCompletions`
- `GameEngineDiscipleOps.kt` — `updateDiscipleStatus`, `releaseDiscipleFromAllSlotsAtomic`, `dismissDisciple`, `expelDisciple`, `syncAllDiscipleStatuses`, `resetAllDisciplesStatus`
- `GameEngineAtomicAssign.kt` — 6 个原子方法 (`assignToResidenceAtomic`, `removeFromResidenceAtomic`, `assignPatrolAtomic`, `removePatrolAtomic`, `swapPatrolAtomic`, `autoAssignPatrolAtomic`)
- `GameEngineProductionOps.kt` — `startAlchemy`, `startForging`, `toggleAutoRestart`

### 改造方案

在所有上述方法内部添加 `withContext(gameDispatcher)` 包裹，使这些 API 从任意线程调用时自动切换到引擎线程。
`withContext(gameDispatcher)` 在已处于引擎线程时是 no-op（Kotlin 协程运行时自动优化跳过调度）。
对 `DomainResult.catching` 返回模式的原子方法需将 `withContext` 包裹在返回值外部。

## Detekt 预存违规 baseline（⏸️ 低优先级）

detekt 配置 `baseline` 只缩不增（CLAUDE.md 13.2），当前未启用 baseline，以下为预存违规，
未被 baseline 记录但已存在于基线中，新代码不允许新增同类违规：

| 违规类型 | 文件 | 说明 |
|---------|------|------|
| `TooGenericExceptionCaught` | `ModeSelectionScreen.kt:226` | catch(Exception) 应缩小范围 |
| `MatchingDeclarationName` | `SaveSelectScreen.kt` | 文件名与顶层声明名不匹配 |
| `MaxLineLength` | `GameStateStoreImpl.kt:607` | 错误消息超 80 字符 |
| `ReturnCount` | `AdServiceImpl.kt:43` | watchAd 3 个 return 超限 2 |
| `MaxLineLength` | `MainActivity.kt:405-406` | 2 行超 80 字符 |
| `WildcardImport` | `ModeSelectionScreen.kt:8,11` | 2 处通配符 import |

治理策略：后续重构逐步修复，不纳入当前 PR。

## 生命周期 ⏸️ 项（低优先级）

以下为 `BootPhase/RunState` 双层状态机的低优先级待优化项，当前行为正确，无用户可见问题：

1. **重入串行化硬屏障** — 当前 CAS 软屏障工作正常，可改用 `Mutex.withLock` 加固
2. **LOADING 状态可达补充** — 初次加载补充 `setLoading()`，纯 UI 优化
3. **取消时状态自动回滚** — 记录初始状态，取消时自动恢复，极少触发

## 写入守卫架构债务（⏸️ 低优先级）

详见 [architecture-debt-write-guard.md](architecture-debt-write-guard.md)：
- `store` 底层存储绕过守卫
- `requireWrite` / `onWrite` 为 `@JvmField var`

## 云存档序列化双路径同步债务（✅ 已落地守卫 — 远期可选优化）

### 已完成
- ✅ **守卫测试已落地** — `SerializationCoverageTest` 等 6 个测试文件覆盖 GameData + 全部嵌套类型。
  新增 GameData 字段时，守卫测试自动失败并提示同步 3 处（SerializableGameData / convertGameData / convertBackGameData）。

### 远期优化（⏸️ 可选）
- **`SerializableBattleLogMember` 字段命名对齐域模型** — `discipleId` vs `id`、`remainingHp` vs `hp`、`remainingMp` vs `mp`
- **方案 C：KSP 代码生成** — 从 GameData 声明自动生成 SerializableGameData 和 converter，消除手动同步。已非必需。

## Crash 1: ANR #5076 — TapTap Sandbox Toast 主线程阻塞（⚠️ 待根治）

**当前措施**：Looper 主线程超时监控（>3s 告警日志）。

**根因**：TapTap SDK 沙箱环境 hook `INotificationManager.enqueueToast`，`SandboxTapAccountChecker.onCheckAccountPass` 触发 `Toast.show()` 时 Binder 调用在主线程堵塞 >5s。

**根治难点**：膨胀点在 TapTap SDK 内部（`com.taptap.sandbox.client.hook`），应用侧无法直接干预。

**待选方案**：
1. **SDK 升级** — 等待 TapTap SDK 修复沙箱 Toast hook 的同步 Binder 调用
2. **反射拦截** — 在 TapTap 初始化前用反射替换 `INotificationManager` 代理（Android API 依赖，兼容性风险）
3. **提前初始化** — 将 TapTap SDK init 提前到 `Application.onCreate()`，使账号检查在用户交互前完成

**状态**：⏸️ 待 TapTap SDK 更新或确定实施方案。

## Crash 2: SIGSEGV #3088 — vulkan.adreno.so vkGetDeviceQueue（⚠️ 待根治）

**当前措施**：
- VulkanBackend.cpp: vkGetDeviceQueue 重试 3 次（2ms 间隔）+ VK_NULL_HANDLE 检查
- VulkanPolicy.kt: 新增 Adreno 崩溃相关机型黑名单

**根因**：某些 Adreno GPU 驱动（国产 OEM ROM）在 `vkCreateDevice()` 后立即调用 `vkGetDeviceQueue()` 时存在竞争条件，队列句柄未就绪时访问导致 SIGSEGV。

**根治难点**：
- SIGSEGV 是 POSIX 信号级崩溃，C++ try-catch 无法捕获
- 延时重试降低竞争窗口但无法 100% 消除
- 黑名单需要持续从 Bugly 收集崩溃数据扩充

**待选方案**：
1. **动态黑名单** — 从 Bugly 或远程配置拉取已知问题设备列表，启动时直接降级到软件渲染
2. **GPU 驱动版本检测** — 在 `VulkanPolicy.getRenderStrategy()` 中检查 `vkPhysicalDeviceProperties.driverVersion`，驱动版本低于已知稳定版本的设备走 SOFTWARE_ONLY
3. **信号处理** — 在 C++ 层用 `sigaction` + `sigsetjmp`/`siglongjmp` 捕获 SIGSEGV（Android API 30+ seccomp-bpf 可能限制）

**状态**：⏸️ 待扩充黑名单或实施信号处理方案。

## ProtoBuf 默认值编码治理（⏸️ P1 待完成）

`NullSafeProtoBuf.protoBuf` 已从 `encodeDefaults = true` 改为 `false`，符合 Proto3 规范。

### 已完成
- ✅ `encodeDefaults = false` — 消除 nullable 字段 null 值序列化崩溃
- ✅ `ProtoNumberCoverageTest` 守卫 — `@Transient` 跳过 + EXCLUDED_FIELDS 清理
- ✅ `GameData.kt` — 7 个运行时字段加 `@kotlinx.serialization.Transient`

### 待完成

**1. 关键字段加 `@EncodeDefault(ALWAYS)`**

`encodeDefaults = false` 后，值为默认值的字段不会被写入二进制。以下字段的默认值非 Proto3 零值，或作为版本标识必须始终写入：

```kotlin
// SaveData.kt
@EncodeDefault(EncodeDefault.Mode.ALWAYS)
@ProtoNumber(1) val version: String = GameConfig.Game.VERSION,

// GameData.kt — 非零默认值字段都需要标注
@EncodeDefault(EncodeDefault.Mode.ALWAYS)
@ProtoNumber(2) var sectName: String = "青云宗",     // 非 ""
@EncodeDefault(EncodeDefault.Mode.ALWAYS)
@ProtoNumber(4) var gameYear: Int = 1,                 // 非 0
@EncodeDefault(EncodeDefault.Mode.ALWAYS)
@ProtoNumber(5) var gameMonth: Int = 1,                // 非 0
// ... 以及其他非零默认值的字段
```

**2. 编码规范补充**

在 `CLAUDE.md` 或编码规范中新增：

> **新增 `@ProtoNumber` 字段规则：** 字段默认值如果不是该类型的零值（`0`/`""`/`false`/`emptyList()`），必须标注 `@EncodeDefault(EncodeDefault.Mode.ALWAYS)`，否则 `encodeDefaults = false` 下该字段不会被写入二进制。

**3. 守卫测试增强**（P2 可选）

在 `ProtoNumberCoverageTest` 中追加：反射检查所有 `@ProtoNumber` 字段，如果默认值 != 类型零值且未标注 `@EncodeDefault(ALWAYS)`，则测试失败。这样新增字段时自动提醒。

### 影响面

- 此优化不会产生用户可见变化（当前运行行为正确）
- 纯防御性改进，防止未来改默认值时出现微妙的存档兼容问题
- 工作量预估：扫描约 150 个 `@ProtoNumber` 字段 + 标注非零默认值字段 + 更新编码规范

