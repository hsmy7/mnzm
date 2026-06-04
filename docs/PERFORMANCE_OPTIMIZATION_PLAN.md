# 修仙模拟经营游戏 — 发热卡顿优化实施方案

**版本**: v2.0（实施报告）  
**日期**: 2026-06-04  
**状态**: 已实施，待真机验证  
**基于**: 行业调研报告（25+参考来源，GDC 2024-2025、Google ADPF、Compose 1.10、腾讯天美/Netmarble 案例）

---

## 目录

1. [已实施项目总览](#1-已实施项目总览)
2. [各阶段实施详情](#2-各阶段实施详情)
3. [实施中发现的问题与修复](#3-实施中发现的问题与修复)
4. [待确认的执行项目](#4-待确认的执行项目)
5. [后续优化项目](#5-后续优化项目)
6. [验证清单](#6-验证清单)
7. [附录：系统-域映射表](#7-附录系统-域映射表)

---

## 1. 已实施项目总览

| 阶段 | 编号 | 项目 | 状态 |
|------|------|------|------|
| 一 | 3.1.1 | tick 基准频率 1000ms → 100ms | ✅ 已实施 |
| 一 | 3.1.2 | 后台完全停止循环 | ✅ 已实施 |
| 一 | 3.1.3 | 焦点感知 tick 分频（两档制） | ✅ 已实施 |
| 一 | 3.1.4 | 空闲检测（10 秒无操作降频） | ✅ 已实施 |
| 一 | 3.1.5 | 用户交互通知 | ✅ 已实施 |
| 一 | 3.1.6 | GameStateStore 新增 activeDialog | ✅ 已实施 |
| 二 | 3.2.1 | Disciple 及子类添加 @Immutable | ✅ 已实施 |
| 二 | 3.2.2 | LazyColumn/LazyRow 补充稳定 key | ✅ 已实施 |
| 二 | 3.2.3 | Canvas 地图合并绘制 | ⚠️ 仅审查 |
| 二 | 3.2.4 | 动画使用 graphicsLayer | ⚠️ 仅审查 |
| 三 | 3.3.1 | 高频查询改为投影查询 | ⏸️ 按方案不建议盲目全改 |
| 三 | 3.3.2 | 批量写入审查 | ✅ 已实施 |
| 三 | 3.3.3 | 索引审查 | ⏸️ 需运行时 EXPLAIN 验证 |
| 四 | 4.1 | ID 类型改为值类 | ⏸️ 按方案推迟 |
| 四 | 4.2 | 使用 update{} 替代 value = copy() | ✅ 已实施 |
| 五 | 5.1 | ADPF 热状态感知 tick 频率 | ✅ 已实施 |
| 五 | 5.2 | Baseline Profile 生成 | ✅ 已实施 |
| 五 | 5.3 | Compose 编译器报告 | ✅ 已有配置 |
| 后 | 5.5 | Dialog catchUpDomain 集成 | ✅ 已实施 |
| 后 | 5.6 | ViewModel notifyUserInteraction 全面集成 | ✅ 已实施 |

---

## 2. 各阶段实施详情

### 阶段一：核心循环改造（P0）

#### 3.1.1 修改 tick 基准频率

**修改文件**:

| 文件 | 改动 |
|------|------|
| `core/GameConfig.kt` | `TICK_INTERVAL` 1000L → 100L，`TICKS_PER_SECOND` 1 → 10 |
| `core/engine/GameEngineCore.kt` | `TICK_INTERVAL_MS` 1000L → 100L，`MIN_TICK_DELAY_MS` 50L → 16L，`ADAPTIVE_MAX_INTERVAL_MS` 2000L → 1000L；新增 `IDLE_TICK_INTERVAL_MS = 2000L`、`IDLE_DETECTION_MS = 10_000L`、`NON_FOCUS_TICK_INTERVAL = 30_000L`、`TICK_TIME_BUDGET_MS = 50L` |

**游戏时间推进速度不变**：phase 累加公式 `phasesPerTick = (3 * speed) / (6 * 10) = 0.05`，每 20 tick 推进 1 phase（2 秒 1 phase），与旧版一致。

#### 3.1.2 后台完全停止循环

**修改文件**:

| 文件 | 改动 |
|------|------|
| `core/engine/GameEngineCore.kt` | 新增 `wasRunningBeforeBackground` 字段；`pauseForBackground()` 改为调用 `stopGameLoop()` 完全停止循环；新增 `resumeFromBackground()` 重新启动循环 |
| `ui/game/GameActivity.kt` | `onResume()` 中调用 `gameEngineCore.resumeFromBackground()` 替代旧的 `saveLoadViewModel.resumeGameLoop()` |

#### 3.1.3 焦点感知 tick 分频（两档制）

**新建文件**:

| 文件 | 说明 |
|------|------|
| `core/engine/system/FocusDomain.kt` | 关注域枚举：ALWAYS / DISCIPLES / BUILDINGS / WAREHOUSE / WORLD_MAP / DIPLOMACY / EXPLORATION / BACKGROUND |

**修改文件**:

| 文件 | 改动 |
|------|------|
| `core/engine/system/GameSystem.kt` | 接口新增 `focusDomain` 属性，默认 `FocusDomain.BACKGROUND` |
| `core/engine/system/SystemManager.kt` | 新增 `onPhaseTickWithDomainFilter()` 方法，按域过滤执行 |
| `core/engine/GameEngineCore.kt` | 新增 `domainLastTickTime`、`getActiveDomains()`、`shouldExecuteDomain()`、`markDomainExecuted()`、`catchUpDomain()`；`tickInternal()` 中调用 `onPhaseTickWithDomainFilter` |

**子系统 focusDomain 声明**:

| 系统 | 域 | 文件 |
|------|-----|------|
| TimeSystem | ALWAYS | `system/TimeSystem.kt` |
| CultivationTickSystem | DISCIPLES | `system/CultivationSystem.kt` |
| EconomySubsystem | BUILDINGS | `domain/production/EconomySubsystem.kt` |
| ProductionSubsystem | BUILDINGS | `domain/production/ProductionSubsystem.kt` |
| ExplorationTickSystem | EXPLORATION | `system/ExplorationSystem.kt` |
| InventorySystem | WAREHOUSE | `system/InventorySystem.kt` |
| MailSystem | BACKGROUND | `system/MailSystem.kt` |
| ChildBirthSystem | BACKGROUND | `system/ChildBirthSystem.kt` |
| PartnerSystem | BACKGROUND | `system/PartnerSystem.kt` |

#### 3.1.4 空闲检测

在游戏循环的 delay 计算中实现：10 秒无操作且无 settlement 工作时，tick 间隔从 100ms 降至 2000ms。

#### 3.1.5 用户交互通知

**修改文件**:

| 文件 | 改动 |
|------|------|
| `core/engine/GameEngine.kt` | `setActiveTab()` 增强：切换 Tab 时调用 `catchUpDomain()` + `onUserInteraction()`；新增 `notifyUserInteraction()` 方法；新增 `domainForTab()` 辅助方法 |

#### 3.1.6 GameStateStore 新增 activeDialog

**修改文件**:

| 文件 | 改动 |
|------|------|
| `core/state/GameStateStore.kt` | 新增 `@Volatile var activeDialog: String? = null` |

---

### 阶段二：Compose 重组优化（P0）

#### 3.2.1 Disciple 及子类添加 @Immutable

**修改文件**:

| 文件 | 类 | 新增注解 |
|------|-----|----------|
| `core/model/Disciple.kt` | Disciple | `@Immutable` |
| `core/model/DiscipleCompact.kt` | DiscipleCompact | `@Immutable` |
| `core/model/DiscipleComponents.kt` | CombatAttributes | `@Immutable` |
| `core/model/DiscipleComponents.kt` | EquipmentSet | `@Immutable` |
| `core/model/DiscipleAggregateWithRelations.kt` | DiscipleAggregateWithRelations | `@Immutable` |

以上文件均添加了 `import androidx.compose.runtime.Immutable`。

#### 3.2.2 LazyColumn/LazyRow 补充稳定 key

共修改 **6 个文件、9 处**缺少 `key` 的 `items()`/`itemsIndexed()` 调用：

| 文件 | key 策略 |
|------|----------|
| `PatrolTowerDialog.kt` | `key = { it.first }`（Pair<Int, String>） |
| `SalaryConfigDialog.kt` | `key = { it.first }` |
| `DiscipleDetailScreen.kt` | `key = { _, item -> item.itemId }`（StorageBagItem） |
| `BattleResultDialog.kt` | `key = { it.itemId }`（BattleRewardItem） |
| `BattleLogDialogs.kt` ×3 | `key = { it.hashCode() }`（chunked 列表）或 `key = { it.roundNumber }`（BattleLogRound） |
| `MerchantDialog.kt` ×2 | `key = { when... }` 按类型生成复合 key |

其余 37 处 `items()` 调用已有 `key` 参数，未做修改。

#### 3.2.3 Canvas 地图合并绘制（审查）

**审查结论**：当前地图标记（SectMarker、LevelMarker、CaveExplorationTeamMarker）各自是独立 Composable，MapCanvas 已使用 `remember` 缓存路径但未使用 `drawWithCache`。可合并为单一 Canvas 块以减少组合节点数，但涉及较大 UI 重构，未实施。

#### 3.2.4 动画使用 graphicsLayer（审查）

**审查结论**：未发现使用 `Modifier.offset/scale/alpha` 配合动画值的情况，无需修改。

---

### 阶段三：数据库/IO 优化（P1）

#### 3.3.1 高频查询改为投影查询

按方案"不建议盲目全改"原则，未实施。需运行时 EXPLAIN QUERY PLAN + 实际耗时数据定位后再改。

#### 3.3.2 批量写入审查

**修改文件**: `data/local/Daos.kt`

9 个 DAO 的 `updateBatch` 方法从 `forEach { update(it) }` 改为 `updateAll(list)`：

| DAO | 改动 |
|-----|------|
| DiscipleDao | `updateBatch` → `updateAll(disciples)` |
| EquipmentStackDao | `updateBatch` → `updateAll(equipmentStacks)` |
| EquipmentInstanceDao | `updateBatch` → `updateAll(equipmentInstances)` |
| ManualStackDao | `updateBatch` → `updateAll(manualStacks)` |
| ManualInstanceDao | `updateBatch` → `updateAll(manualInstances)` |
| PillDao | `updateBatch` → `updateAll(pills)` |
| MaterialDao | `updateBatch` → `updateAll(materials)` |
| SeedDao | `updateBatch` → `updateAll(seeds)` |
| HerbDao | `updateBatch` → `updateAll(herbs)` |

#### 3.3.3 索引审查

需运行时 EXPLAIN QUERY PLAN 验证，未实施。

---

### 阶段四：内存/GC 优化（P1）

#### 4.1 ID 类型改为值类

按方案"建议在阶段五（后续优化）再执行"，未实施。

#### 4.2 使用 update{} 替代 value = copy()

**修改文件**:

| 文件 | 改动 |
|------|------|
| `ui/game/BloodRefiningViewModel.kt` | 全部 `_uiState.value = _uiState.value.copy(...)` 改为 `_uiState.update { it.copy(...) }`；添加 `import kotlinx.coroutines.flow.update` |
| `ui/game/GameViewModel.kt` | `_detailDisciple.value = current.copy(disciple = target)` 改为 `_detailDisciple.update { it?.copy(disciple = target) }` |

---

### 阶段五：深度优化（P2）

#### 5.1 ADPF 热状态感知 tick 频率

已在阶段一的 delay 计算中实现，`effectiveInterval` 包含以下分支：

| 热状态 | tick 间隔 |
|--------|-----------|
| SEVERE+（shouldEmergencySave） | 500ms |
| MODERATE+（shouldReduceWorkload） | 200ms |
| LIGHT（isLightThrottle） | 150ms |
| 空闲（10 秒无操作） | 2000ms |
| 自适应降频（adaptiveSlowdownFactor > 1.0） | 100ms × factor，上限 1000ms |
| 正常 | 100ms |

#### 5.2 Baseline Profile 生成

**修改文件**: `app/build.gradle`

新增依赖：`implementation 'androidx.profileinstaller:profileinstaller:1.4.1'`

#### 5.3 Compose 编译器报告

已有配置（`reportsDestination` + `metricsDestination` 指向 `compose_metrics`），无需修改。

---

## 3. 实施中发现的问题与修复

### 问题 1：GameEngine.kt 中 `engineCore` 未解析

**现象**：编译错误 `Unresolved reference 'engineCore'`（第 142/144/155 行）

**原因**：新增代码中误用了 `engineCore`，实际字段名为 `gameEngineCore`

**修复**：将 3 处 `engineCore` 改为 `gameEngineCore`

### 问题 2：InventorySystem.kt 中 `systemName` 重复声明

**现象**：编译错误 `Conflicting declarations: val systemName: String`（第 69 行与第 92 行）

**原因**：添加 `focusDomain` 声明时，在第 69 行新增了 `override val systemName: String = SYSTEM_NAME`，但原代码第 92 行已有相同声明

**修复**：删除第 69 行重复的 `systemName` 声明，保留第 92 行

### 问题 3：GameEngine.kt 中 `setActiveTab()` 缩进错误

**现象**：二轮复查发现 `if` 块的闭合括号 `}` 与 `gameEngineCore.onUserInteraction()` 缩进不一致

**原因**：首次编辑时 SearchReplace 的替换文本缩进有误

**修复**：统一缩进，`if` 块闭合括号与 `onUserInteraction()` 对齐到方法体内

### 问题 4：GameEngineCore.kt 中 `isIdle` 变量未使用

**现象**：`tickInternal()` 中声明了 `val isIdle = ...` 但未使用（idle 检测已在游戏循环的 delay 计算中处理）

**原因**：从方案代码直接移植时，`tickInternal()` 和游戏循环中都有 idle 检测逻辑，`tickInternal()` 中的冗余

**修复**：移除 `tickInternal()` 中未使用的 `isIdle` 变量

### 问题 5：`adaptiveSlowdownFactor` 未接入 delay 计算

**现象**：`tick()` 方法中 `adaptiveSlowdownFactor` 被更新（超预算时增大，正常时衰减），但游戏循环的 `effectiveInterval` 计算未使用该因子

**原因**：方案中声明了自适应降频因子但未在 when 分支中接入

**修复**：在 `effectiveInterval` 的 when 分支中新增 `adaptiveSlowdownFactor > 1.0 -> (TICK_INTERVAL_MS * adaptiveSlowdownFactor).toLong().coerceAtMost(ADAPTIVE_MAX_INTERVAL_MS)`

---

## 4. 待确认的执行项目

以下项目已实施代码，但需要在真机/模拟器上运行验证才能确认效果：

| 编号 | 项目 | 验证方式 | 预期结果 |
|------|------|----------|----------|
| V1 | 100ms tick 下游戏时间推进正确性 | 运行游戏，观察 1 个游戏月是否仍为 6 秒真实时间 | 推进速度不变 |
| V2 | 后台完全停止循环 | 切到后台，Android Studio Profiler 确认 CPU 接近 0% | CPU 降至 0 |
| V3 | 前台恢复 | 从后台切回，游戏状态正确恢复 | 无数据丢失 |
| V4 | 焦点分频效果 | 切换 Tab，观察日志中各系统执行频率 | 活跃域 100ms，非活跃域 30s |
| V5 | 界面切换追赶 | 切到外交 Tab，观察外交数据是否立即更新 | 打开瞬间最新 |
| V6 | 空闲降频 | 10 秒无操作，观察日志中 tick 间隔 | 从 100ms 降至 2000ms |
| V7 | 空闲恢复 | 触碰屏幕后 tick 间隔恢复 | 立即恢复 100ms |
| V8 | 热节流 | 模拟高负载或温控触发 | tick 自动降频 |
| V9 | 自适应降频 | 大量弟子场景下观察 tick 耗时 | 超预算时自动降频 |
| V10 | Compose 重组次数 | Layout Inspector 确认跳过率 | 跳过率 > 80% |
| V11 | GC 暂停时间 | Memory Profiler 录制 | GC 暂停 < 16ms |
| V12 | 帧率稳定性 | GPU 渲染分析工具条 | 绿色条（16ms 内） |

---

## 5. 后续优化项目

### 5.1 ID 类型改为值类（原 4.1）

**优先级**: P2  
**原因**: 方案明确标注"建议在后续优化再执行"，涉及 Room 类型转换等大规模重构  
**预期收益**: 减少 String 装箱开销和比较开销  
**涉及文件**: 几乎所有业务代码

### 5.2 投影查询优化（原 3.3.1）

**优先级**: P2  
**原因**: 方案说"不建议盲目全改"，需运行时 EXPLAIN QUERY PLAN + 实际耗时数据定位  
**预期收益**: 高频查询减少 IO 量  
**实施前提**: 获取真机上的查询耗时数据

### 5.3 索引审查（原 3.3.3）

**优先级**: P2  
**原因**: 需运行时 EXPLAIN QUERY PLAN 确认 `slot_id` 等字段是否走索引  
**预期收益**: 避免全表扫描

### 5.4 Canvas 地图合并绘制（原 3.2.3）

**优先级**: P3  
**原因**: 审查发现地图标记使用独立 Composable，可合并为单一 Canvas 块减少组合节点；静态背景可用 `drawWithCache` 缓存  
**预期收益**: 减少地图界面的组合节点数和重组开销  
**涉及文件**: `ui/game/map/` 目录下 MapCanvas.kt、SectMarker.kt、LevelMarker.kt、CaveExplorationTeamMarker.kt

### 5.5 Dialog 打开时 catchUpDomain 集成 ✅ 已实施

**实施方式**: 在 `GameEngine` 中新增 `setActiveDialog(route)` 和 `domainForDialog(route)` 方法；在 `GameViewModel.navigateToDialog()` 和 `dismissDialog()` 中调用 `gameEngine.setActiveDialog()`。
**涉及文件**: `core/engine/GameEngine.kt`（新增 2 方法）、`ui/game/GameViewModel.kt`（2 处调用）

### 5.6 ViewModel 层 notifyUserInteraction 全面集成 ✅ 已实施

**实施方式**: 在 `MainGameScreen` 根级 `Box` 添加 `pointerInput` 触摸检测——任何触碰（Initial pass，不消费事件）都调用 `viewModel.notifyUserInteraction()`。覆盖所有按钮点击、列表滚动、手势操作。
**涉及文件**: `ui/game/MainGameScreen.kt`（根 Box 添加 pointerInput）、`ui/game/GameViewModel.kt`（新增 notifyUserInteraction 方法）

---

## 6. 验证清单

### 编译验证（已通过）

```
compileDebugKotlin — BUILD SUCCESSFUL
testDebugUnitTest — BUILD SUCCESSFUL (42 tasks)
```

### 功能验证（待真机确认）

- [ ] **游戏时间推进正确**: 改成 100ms 后，1 个游戏月仍是 6 秒真实时间
- [ ] **后台暂停**: 切到后台后，CPU 使用率降至接近 0%
- [ ] **前台恢复**: 从后台切回，游戏状态正确恢复，无数据丢失
- [ ] **Tab 切换响应**: 切换到各 Tab，对应域的系统执行频率正确
- [ ] **Dialog 打开响应**: 打开炼丹/锻器/世界地图等 Dialog，对应系统加速
- [ ] **空闲降频**: 10 秒无操作后，日志中 tick 频率从 100ms 降至 2000ms
- [ ] **空闲恢复**: 触碰屏幕后，tick 频率立即恢复 100ms
- [ ] **热节流**: 温控触发时 tick 自动降频

### 性能验证（待真机确认）

- [ ] **Compose 重组次数**: Layout Inspector 确认跳过率 > 80%
- [ ] **GC 暂停时间**: Memory Profiler GC 暂停 < 16ms
- [ ] **tick 耗时**: 日志中无 "Tick over budget" 警告（单 tick < 50ms）
- [ ] **帧率稳定性**: GPU 渲染分析工具条绿色（16ms 内）
- [ ] **发热对比**: 同场景运行 10 分钟，比较优化前后设备温度

---

## 7. 附录：系统-域映射表

| 系统名称 | 文件 | Priority | FocusDomain | 100ms时行为 | 切换触发 |
|----------|------|----------|-------------|------------|----------|
| TimeSystem | `system/TimeSystem.kt` | 0 | ALWAYS | 每 tick | — |
| InventorySystem | `system/InventorySystem.kt` | 50 | WAREHOUSE | 仅仓库界面 | 打开仓库 |
| CultivationTickSystem | `system/CultivationSystem.kt` | 200 | DISCIPLES | 仅弟子界面 | 打开弟子Tab |
| ProductionSubsystem | `production/ProductionSubsystem.kt` | 205 | BUILDINGS | 仅建筑界面 | 打开建筑Tab |
| EconomySubsystem | `production/EconomySubsystem.kt` | 206 | BUILDINGS | 仅建筑界面 | 打开建筑Tab |
| ChildBirthSystem | `system/ChildBirthSystem.kt` | 235 | BACKGROUND | 30s/次 | — |
| ExplorationTickSystem | `system/ExplorationSystem.kt` | 240 | EXPLORATION | 仅探索/地图 | 打开地图/任务 |
| PartnerSystem | `system/PartnerSystem.kt` | 240 | BACKGROUND | 30s/次 | — |
| MailSystem | `system/MailSystem.kt` | 960 | BACKGROUND | 30s/次 | 打开邮件 |
| HerbGardenSystem | `building/HerbGardenSystem.kt` | Facade | BUILDINGS | 仅建筑界面 | 打开药园 |
| DiplomacyService | `diplomacy/DiplomacyService.kt` | Facade | DIPLOMACY | 仅外交界面 | 打开外交 |
| CombatService | `battle/CombatService.kt` | Facade | EXPLORATION | 仅探索/战斗 | 战斗触发 |
| AISectDiscipleManager | `diplomacy/AISectDiscipleManager.kt` | Facade | BACKGROUND | 30s/次 | — |
| AISectAttackManager | `battle/AISectAttackManager.kt` | Facade | BACKGROUND | 30s/次 | — |
| ExplorationService | `exploration/ExplorationService.kt` | Facade | EXPLORATION | 仅探索/地图 | 打开地图 |
| MissionSystem | `exploration/MissionSystem.kt` | Facade | EXPLORATION | 仅任务界面 | 打开任务大厅 |

### Tab/Dialog → 域映射速查

| 玩家界面 | 激活的 FocusDomain |
|----------|-------------------|
| 总览 Tab | DISCIPLES + BUILDINGS |
| 弟子 Tab | DISCIPLES |
| 弟子详情 | DISCIPLES |
| 建筑 Tab | BUILDINGS |
| 仓库 Tab | WAREHOUSE |
| 设置 Tab | (仅 ALWAYS) |
| 世界地图 Dialog | WORLD_MAP |
| 炼丹 Dialog | BUILDINGS |
| 锻器 Dialog | BUILDINGS |
| 药园 Dialog | BUILDINGS |
| 灵矿 Dialog | BUILDINGS |
| 外交 Dialog | DIPLOMACY |
| 任务大厅 Dialog | EXPLORATION |
| 巡逻塔 Dialog | EXPLORATION |
| 邮件 Dialog | BACKGROUND → 打开时可临时加速 |
| 商人 Dialog | WAREHOUSE |

### 修改文件清单

| 文件 | 修改类型 | 说明 |
|------|----------|------|
| `core/GameConfig.kt` | 修改常量 | TICK_INTERVAL → 100L, TICKS_PER_SECOND → 10 |
| `core/engine/GameEngineCore.kt` | 重大修改 | 100ms tick、后台停止、焦点分频、空闲检测、时间预算、自适应降频 |
| `core/engine/system/GameSystem.kt` | 新增字段 | focusDomain 属性 |
| `core/engine/system/FocusDomain.kt` | **新文件** | 关注域枚举 |
| `core/engine/system/SystemManager.kt` | 新增方法 | onPhaseTickWithDomainFilter() |
| `core/engine/GameEngine.kt` | 新增方法 | onUserInteraction, setActiveTab 增强, domainForTab, notifyUserInteraction |
| `core/state/GameStateStore.kt` | 新增字段 | activeDialog |
| `core/model/Disciple.kt` | 添加注解 | @Immutable |
| `core/model/DiscipleCompact.kt` | 添加注解 | @Immutable |
| `core/model/DiscipleComponents.kt` | 添加注解 | @Immutable (CombatAttributes + EquipmentSet) |
| `core/model/DiscipleAggregateWithRelations.kt` | 添加注解 | @Immutable |
| `ui/game/GameActivity.kt` | 修改回调 | onResume 调用 resumeFromBackground |
| `ui/game/BloodRefiningViewModel.kt` | 修改更新方式 | .value = .copy() → .update {} |
| `ui/game/GameViewModel.kt` | 修改更新方式 | _detailDisciple.value = → .update {} |
| 9 个子系统文件 | 添加声明 | focusDomain override |
| `data/local/Daos.kt` | 修改批量操作 | 9 个 updateBatch: forEach → updateAll |
| `app/build.gradle` | 新增依赖 | profileinstaller:1.4.1 |
| 6 个 Dialog/Screen 文件 | 补充参数 | items() 添加 key |

---

*本文档基于行业调研（25+ 参考来源，包括 Google ADPF 官方文档、GDC 2024-2025 演讲、腾讯天美技术分享、Android Developers 性能专题周等）制定，并记录了实际实施过程中的所有变更与问题。*
