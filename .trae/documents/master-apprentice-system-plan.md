# 师徒机制 (Master-Apprentice System) 实施计划

## Summary

新增师徒机制：玩家可控制弟子向其他弟子拜师。师徒关系永久绑定，仅一方死亡方可解绑。师父最多 5 名徒弟，弟子最多 1 名师父。师父对徒弟按"大境界差"提供修炼速度 +5%/级、突破率 +3%/级 加成。涉及数据模型、引擎计算、缓存指纹、死亡清理、UI（拜师按钮 + 选择界面 + 确认弹窗 + 关系面板 + 突破率详情）。

## 实施进度（全部完成 ✅）

| 阶段 | 状态 | 说明 |
| --- | --- | --- |
| A. 数据层 (A1–A5) | ✅ 已完成 | SocialData/DiscipleExtended/DiscipleAggregate/DiscipleTables 已新增 masterId；GameDatabase 已升至 v10 |
| B. 引擎层 (B1–B6) | ✅ 已完成 | DiscipleStatCalculator（常量+公式+masterDiscipleBonus 参数，含 MAX_APPRENTICES_PER_MASTER 常量）/ SettlementCache / SettlementCoordinator / DiscipleBreakthroughHandler / DiscipleLifecycleProcessor / DiscipleService.apprenticeToMaster / CultivationCore 师徒注入 / DiscipleFacade(Impl) / GameEngineDiscipleOps 扩展 |
| C1. DetailHeaderSection 拜师按钮 | ✅ 已完成 | onShowApprentice 参数已加；驱逐按钮后已新增拜师 Box（hasMaster 灰禁用） |
| C2. DiscipleDetailScreen 接线+确认弹窗 | ✅ 已完成 | DetailRightPanel 调用处已传 onShowApprentice；3 个状态变量 + MasterApprenticeSelectDialog 渲染 + StandardPromptDialog 确认弹窗 |
| C3. MasterApprenticeSelectDialog | ✅ 已完成 | 文件已存在，半屏+筛选+卡片网格+点击弹确认 |
| C4. DetailActionButtons 关系面板 | ✅ 已完成 | RelationsDialog 已含 master 师父 + apprentices 徒弟分类 |
| C5. DetailCultivationSection 突破率详情 | ✅ 已完成 | masterDiscipleBonus 计算+传入+BreakthroughDetailDialog buildList 含"师徒加成" |
| C6. ViewModel/Delegate 暴露 | ✅ 已完成 | DiscipleDelegate.apprenticeToMaster + GameViewModel.apprenticeToMaster（第 866-867 行） |
| D. 编译验证 + 测试 | ✅ 已完成 | 全模块 compileReleaseKotlin 通过；新增 19 个师徒系统单元测试 + 既有 863 个测试全通过 |
| E. Changelog | ✅ 已完成 | CHANGELOG.md + changelog_entries.json 已添加 v4.0.25 条目 |
| F. 常量统一 | ✅ 已完成 | MAX_APPRENTICES_PER_MASTER 提升至 DiscipleStatCalculator companion object |

### 补充实现（计划未列但已完成的额外影响点）

- **CultivationCore**：聚焦弟子的修炼速度计算已注入师徒加成（`CultivationCore.kt:155-161`）
- **DiscipleSelectorDialog**：增强了 `DiscipleSelectorConfig`（新增 `defaultSortAttribute` 和 `currentId` 参数），`MasterApprenticeSelectDialog` 复用了该增强

## 核心公式（已确认）

境界数字越小境界越高（练气=9, 筑基=8, 金丹=7, 元婴=6...）。

```
gap = max(0, discipleRealm - masterRealm - 1)
cultivationSpeedBonus = gap * 0.05
breakthroughBonus     = gap * 0.03
```

- 金丹师父(7) + 练气徒弟(9): gap = max(0, 9-7-1) = 1 → 5% 修炼速度 + 3% 突破率 ✓
- 金丹师父(7) + 筑基徒弟(8): gap = max(0, 8-7-1) = 0 → 无加成
- 同境界 / 徒弟境界 ≥ 师父境界: gap = 0 → 无加成 ✓

## Current State Analysis（探查结论）

1. **驱逐按钮**：`DetailHeaderSection.kt` 第 120-123 行，`Row(spacedBy(6.dp))` 内最后一个 `Box`。按钮统一模式 = `Box.clip(RoundedCornerShape(4.dp)).background(color).clickable{}.padding(6,2) + Text`。
2. **社交数据**：`SocialData`（`DiscipleComponents.kt:129-140`）仅含 partnerId/parentId1/parentId2/griefEndYear 等，**无 masterId**。`DiscipleExtended` 表平铺这些字段。`DiscipleTables` 有对应组件表（partnerIds/parentId1s 等）。
3. **修炼速度**：`DiscipleStatCalculator.calculateCultivationSpeed`（第 329-395 行）通过参数注入各项加成（buildingBonus/preachingElderBonus/parentCultivationBonus 等），`SettlementCache.calculateCultivationRate`（第 140-173 行）组装并调用。
4. **缓存指纹**：`CultivationRateFingerprint`（`SettlementCache.kt:34-42`）7 个 Int 字段；`SettlementCoordinator.computeFingerprint`（第 1170-1211 行）计算 `perDiscipleHash`（逐弟子哈希丹药/丧亲/功法/寿命），`realmHash` 捕获境界变化。**未含师徒信息**。
5. **突破率详情**：`BreakthroughBonusDetail`（`DiscipleStatCalculator.kt:534-545`）+ `getBreakthroughBonusDetail`（第 547-577 行）。UI 在 `DetailCultivationSection.kt` `BreakthroughDetailDialog`（第 517-613 行）用 `buildList` 动态列举加成项，3 列网格渲染。
6. **实际突破判定**：`DiscipleBreakthroughHandler.tryBreakthrough`（第 161-204 行）调用 `getBreakthroughChance`，有 `tables`(DiscipleTables) 和 `data` 上下文。
7. **死亡清理**：`DiscipleLifecycleProcessor.handleDiscipleDeath`（第 80-157 行）目前仅清除伴侣 partnerId（第 91-99 行），**未清除师徒**。
8. **可复用 UI 组件**：`UnifiedGameDialog`(半屏) + `SpiritRootAttributeFilterBar`(筛选栏) + `PortraitDiscipleCard`(弟子卡片) + `StandardPromptDialog`(确认框，传 dismissLabel 即双按钮 取消左/确认右)。`DiscipleSelectorDialog`（`dialogs/shared/DiscipleSelectorDialog.kt`）是现成的"半屏+筛选+卡片网格"模板。
9. **弟子操作链路**：`GameViewModel.expelDisciple` → `DiscipleFacade` → `DiscipleFacadeImpl` → `DiscipleService.expelDisciple`（`stateStore.update { ... }` 内操作 discipleTables）。
10. **数据库**：`GameDatabase` version=10（v10: 弟子社交新增 masterId），使用 `fallbackToDestructiveMigration()`。

## Proposed Changes

### A. 数据层（core:domain）

#### A1. `DiscipleComponents.kt` — SocialData 新增 masterId
- 文件：`android/core/domain/src/main/java/com/xianxia/sect/core/model/DiscipleComponents.kt` 第 129-140 行
- 在 `SocialData` 新增 `var masterId: String? = null`
- 更新类注释"共6个字段"→"共7个字段"

#### A2. `DiscipleExtended.kt` — Room 实体新增列
- 文件：`android/core/domain/src/main/java/com/xianxia/sect/core/model/DiscipleExtended.kt`
- 新增 `var masterId: String? = null`（第 28 行 griefEndYear 后）
- `fromDisciple` 中新增 `masterId = disciple.social.masterId`

#### A3. `DiscipleAggregate.kt` — 新增访问器 + 构造
- 文件：`android/core/domain/src/main/java/com/xianxia/sect/core/model/DiscipleAggregate.kt`
- 第 109 行后新增 `val masterId: String? get() = extended?.masterId`
- 第 285-292 行 `SocialData(...)` 构造新增 `masterId = masterId`

#### A4. `DiscipleTables.kt` — 新增 masterIds 组件表
- 文件：`android/core/domain/src/main/java/com/xianxia/sect/core/state/DiscipleTables.kt`
- 第 134 行后新增 `val masterIds = ComponentTable<String?>()`
- insert（第 267-274 行）：`s.masterId?.let { masterIds[id] = it }`
- assemble（第 497-505 行）：`masterId = masterIds.getOrNull(id)`
- remove（约第 572 行）：`masterIds.remove(id)`
- clear（约第 622 行）：`masterIds.clear()`
- onWrite（约第 694 行）：`masterIds.onWrite = cb`
- copy（约第 830 行）：`copyRefTable(this.masterIds, copy.masterIds)`

#### A5. `GameDatabase.kt` — 版本号 9 → 10
- 文件：`android/core/data/src/main/java/com/xianxia/sect/data/local/GameDatabase.kt` 第 80 行
- `version = 10  // v10: 弟子社交新增 masterId（师徒关系）`
- 依赖 `fallbackToDestructiveMigration()` 处理 schema 变更

### B. 引擎层（core:engine）

#### B1. `DiscipleStatCalculator.kt` — 加成计算 + 公式
- 文件：`android/core/engine/src/main/java/com/xianxia/sect/core/domain/disciple/DiscipleStatCalculator.kt`
- 新增常量（companion object）：
  ```kotlin
  const val MASTER_DISCIPLE_CULTIVATION_BONUS_PER_GAP = 0.05
  const val MASTER_DISCIPLE_BREAKTHROUGH_BONUS_PER_GAP = 0.03
  ```
- 新增工具函数：
  ```kotlin
  fun getMasterDiscipleRealmGap(discipleRealm: Int, masterRealm: Int): Int =
      (discipleRealm - masterRealm - 1).coerceAtLeast(0)
  ```
- `calculateCultivationSpeed`（第 329 行，两个重载）新增参数 `masterDiscipleBonus: Double = 0.0`，在 `totalBonus` 中累加（第 386 行后 `totalBonus += masterDiscipleBonus`）
- `BreakthroughBonusDetail`（第 534 行）新增 `val masterDiscipleBonus: Double`
- `getBreakthroughBonusDetail`（第 547 行）新增参数 `masterDiscipleBonus: Double = 0.0`，纳入 total 计算 + 返回值
- `getBreakthroughChance`（第 467 行和第 499 行两个重载）新增参数 `masterDiscipleBonus: Double = 0.0`，纳入 totalBonus

#### B2. `SettlementCache.kt` — 修炼速率注入师徒加成
- 文件：`android/core/engine/src/main/java/com/xianxia/sect/core/domain/settlement/SettlementCache.kt`
- `calculateCultivationRate`（第 140-173 行）：用 `allDisciples` map 查找徒弟的 masterId → master.realm → 调 `getMasterDiscipleRealmGap` → `gap * 0.05` → 传入 `calculateCultivationSpeed(masterDiscipleBonus = ...)`

#### B3. `SettlementCoordinator.kt` — 指纹新增师徒检查
- 文件：`android/core/engine/src/main/java/com/xianxia/sect/core/domain/settlement/SettlementCoordinator.kt`
- `computeFingerprint`（第 1178-1195 行）`perDiscipleHash` 内新增：
  ```kotlin
  h = 31 * h + (tables.masterIds.getOrNull(id)?.hashCode() ?: 0)
  val mid = tables.masterIds.getOrNull(id)?.toIntOrNull()
  h = 31 * h + (mid?.let { tables.realms.getOrDefault(it, 9) } ?: 9)
  ```
  捕获：师徒关系建立/解除（masterId 变化）+ 师父大境界突破（masterRealm 变化）→ 重算徒弟修炼速度。

#### B4. `DiscipleBreakthroughHandler.kt` — 实际突破判定注入加成
- 文件：`android/core/engine/src/main/java/com/xianxia/sect/core/service/DiscipleBreakthroughHandler.kt`
- `tryBreakthrough`（第 161-204 行）：从 `tables.masterIds` 查 masterId → `tables.realms` 查 masterRealm → 计算 gap → `gap * 0.03` → 传入 `getBreakthroughChance(masterDiscipleBonus = ...)`

#### B5. `DiscipleLifecycleProcessor.kt` — 死亡清理师徒关系
- 文件：`android/core/engine/src/main/java/com/xianxia/sect/core/service/DiscipleLifecycleProcessor.kt`
- `handleDiscipleDeath`（第 80-157 行），在伴侣清理（第 91-99 行）后新增：
  ```kotlin
  // 师父死亡 → 清除所有徒弟的 masterId 指向（师徒关系因一方死亡而解绑）
  val deadId = disciple.id
  griefUpdated.indices.forEach { i ->
      if (griefUpdated[i].social.masterId == deadId) {
          griefUpdated[i] = griefUpdated[i].copy(
              social = griefUpdated[i].social.copy(masterId = null)
          )
      }
  }
  ```
  徒弟死亡无需额外清理（师父的徒弟数按存活弟子统计，自然剔除死者；死者 masterId 随死亡失效）。

#### B6. `DiscipleService.kt` + `DiscipleFacade` + `DiscipleFacadeImpl` — 拜师操作
- `DiscipleService.kt`（`android/core/engine/.../disciple/DiscipleService.kt`）新增：
  ```kotlin
  suspend fun apprenticeToMaster(discipleId: String, masterId: String): DomainResult<Unit>
  ```
  逻辑（在 `stateStore.update {}` 内）：
  - 校验 discipleId 与 masterId 均存活
  - 校验 masterId != discipleId
  - 校验该弟子当前无师父（`masterIds[id] == null`）
  - 校验师父徒弟数 < 5（统计 `masterIds` 中值 == masterId 且存活的数量）
  - 通过后 `masterIds[discipleId] = masterId`，写回 tables
- `DiscipleFacade.kt` 第 24 行后新增接口 `suspend fun apprenticeToMaster(discipleId: String, masterId: String): DomainResult<Unit>`
- `DiscipleFacadeImpl.kt` 第 92 行后新增实现委托

### C. UI 层（feature:game）

#### C1. `DetailHeaderSection.kt` — 新增拜师按钮
- 文件：`android/feature/game/.../components/detail/DetailHeaderSection.kt`
- `DetailRightPanel` 签名（第 30-42 行）新增 `onShowApprentice: () -> Unit`
- 第 123 行（驱逐 Box 闭合后、Row 闭合前）新增拜师按钮，沿用 Box+Text 模式：
  ```kotlin
  val hasMaster = disciple.masterId != null
  Box(
      modifier = Modifier.clip(RoundedCornerShape(4.dp))
          .background(if (hasMaster) Color(0xFF9E9E9E) else Color(0xFF8D6E63))
          .clickable(enabled = !hasMaster) { dismissDropdown(); onShowApprentice() }
          .padding(horizontal = 6.dp, vertical = 2.dp)
  ) { Text(if (hasMaster) "已拜师" else "拜师", fontSize = 10.sp, color = Color.White) }
  ```
- 棕色 `0xFF8D6E63` 区分于其他按钮；已有师父时灰色禁用显示"已拜师"。

#### C2. `DiscipleDetailScreen.kt` — 状态接线 + 确认弹窗
- 文件：`android/feature/game/.../DiscipleDetailScreen.kt`
- 新增状态：`showApprenticeSelectDialog`、`selectedMaster: DiscipleAggregate?`、`showApprenticeConfirmDialog`
- `DetailRightPanel` 调用处（第 222-234 行）新增 `onShowApprentice = { showApprenticeSelectDialog = true }`
- 渲染选择界面（`showApprenticeSelectDialog` → `MasterApprenticeSelectDialog`）
- 渲染确认弹窗（`showApprenticeConfirmDialog` → `StandardPromptDialog`）：
  - title = "拜师确认"
  - text = "确认让 ${currentDisciple.name}（${currentDisciple.realmName}）拜 ${selectedMaster.name}（${selectedMaster.realmName}）为师？"
  - confirmLabel = "确认"（右），dismissLabel = "取消"（左）
  - onConfirm → `viewModel?.apprenticeToMaster(currentDisciple.id, selectedMaster.id)`，关闭弹窗
  - onDismiss → 仅关闭确认弹窗

#### C3. 新建 `MasterApprenticeSelectDialog.kt` — 选择拜师界面
- 路径：`android/feature/game/.../components/detail/MasterApprenticeSelectDialog.kt`
- 基于 `DiscipleSelectorDialog` 模板（半屏 + 筛选栏 + 弟子卡片网格）
- 候选过滤：`allDisciples.filter { it.isAlive && it.id != currentDisciple.id && countApprentices(it.id, allDisciples) < 5 }`
  - `countApprentices(masterId, allDisciples)` = 存活弟子中 `masterId == masterId` 的数量
- 点击卡片 → 不立即确认，而是回调 `onMasterSelected(disciple)` 交给父级弹确认框

#### C4. `DetailActionButtons.kt` — 关系面板新增师徒显示
- 文件：`android/feature/game/.../components/detail/DetailActionButtons.kt`
- `RelationsDialog`（第 86-176 行）：
  - 新增 master 查找：`val master = disciple.masterId?.let { discipleMap[it] }`
  - 新增 apprentices 查找：`val apprentices = allDisciples.filter { it.masterId == disciple.id }`
  - 在 buildList 区域新增"师父"分类（master != null 时）和"徒弟"分类（apprentices.isNotEmpty() 时）
  - 空关系判定条件（第 166 行）加入 `master == null && apprentices.isEmpty()`

#### C5. `DetailCultivationSection.kt` — 突破率详情新增师徒加成
- 文件：`android/feature/game/.../components/detail/DetailCultivationSection.kt`
- 注：`getBreakthroughChance` / `getBreakthroughBonusDetail` 的 `masterDiscipleBonus` 参数已在 B1 阶段加入（有默认值 0.0），扩展函数签名同步已完成，本步只需计算并传入值。
- 第 133 行已有 `val discipleMap = allDisciples.associateBy { it.id }`，复用查 master：
  ```kotlin
  val masterDiscipleBonus = disciple.masterId?.let { mid ->
      val master = discipleMap[mid]
      if (master != null && master.isAlive)
          DiscipleStatCalculator.getMasterDiscipleBreakthroughBonus(disciple.realm, master.realm)
      else 0.0
  } ?: 0.0
  ```
- 第 194 行 `disciple.getBreakthroughChance(...)` 传入 `masterDiscipleBonus = masterDiscipleBonus`
- 第 203-209 行 `getBreakthroughBonusDetail(...)` 传入 `masterDiscipleBonus = masterDiscipleBonus`
- `BreakthroughDetailDialog`（第 521-531 行）buildList 新增（位于 adBonus 之后、griefPenalty 之前，与字段顺序一致）：
  ```kotlin
  if (detail.masterDiscipleBonus > 0) add("师徒加成" to detail.masterDiscipleBonus)
  ```

#### C6. `GameViewModel.kt` — 暴露拜师操作
- 文件：`android/feature/game/.../GameViewModel.kt` 第 864 行后（`expelDisciple` 下方）
- `DiscipleDelegate.apprenticeToMaster` 已存在（内部已 `scope.launch`），GameViewModel 仅做简单委托，与 `expelDisciple` 模式一致：
  ```kotlin
  fun apprenticeToMaster(discipleId: String, masterId: String) = disciple.apprenticeToMaster(discipleId, masterId)
  ```

### D. 编译验证 + 既有测试
- 签名同步已在 B1 阶段完成（`getBreakthroughChance`/`getBreakthroughBonusDetail`/`calculateCultivationSpeed` 的 `masterDiscipleBonus` 参数均有默认值 0.0，不破坏现有调用；扩展函数 `DiscipleAggregate.getBreakthroughChance`、`Disciple.getBreakthroughChance` 已同步；`XianxiaApplication` 生产实现、`DiscipleAggregate` noop provider、`CultivationCoreTest` 测试 mock 均已同步）。
- 本步仅验证：全模块 `assembleDebug` 编译通过 + 既有单测（`GameTimeClockTest` 等）通过。

## Assumptions & Decisions

1. **境界显示格式**：确认弹窗中使用现有 `realmName`（如"练气1层"）加全角括号"（练气1层）"，与全项目一致。用户例子中的"一层"为示意。
2. **已有师父时**：拜师按钮灰色禁用显示"已拜师"（关系永久，不可解除；师父死亡后 masterId 被清空，按钮自动恢复可用）。
3. **候选不过滤境界**：选择界面显示所有存活且徒弟未满的弟子（除自己），不按境界过滤。同境界/低境界师父选中后加成自然为 0（符合"境界相同及弟子大于师父境界时无加成"）。
4. **师父死亡解绑**：师父死亡时清除所有徒弟的 masterId（徒弟可重新拜师）。徒弟死亡无需额外清理（师父徒弟数按存活弟子统计）。
5. **DB 迁移**：version 9→10，依赖 `fallbackToDestructiveMigration()`（项目既有约定）。
6. **可同时为师为徒**：不禁止一个弟子既有师父又有徒弟（用户未限制）。
7. **不排除血亲**：候选列表不排除父母/子女/道侣（用户未要求，且师徒与血亲不冲突）。
8. **聚焦弟子实时路径**：`CultivationCore.updateFocusedDisciple` 已同步注入师徒加成（`CultivationCore.kt:155-161`，已验证）。

## Verification Steps

1. **编译**：全模块 `assembleDebug` 通过。
2. **数据库**：升级后 schema 包含 `disciples_extended.masterId` 列。
3. **功能-拜师**：弟子详情 → 拜师按钮 → 选择界面（仅显示存活且徒弟<5的弟子）→ 点击卡片 → 确认弹窗文本格式正确 → 确认后关系建立。
4. **功能-加成生效**：金丹师父+练气徒弟 → 徒弟修炼速度提升 5%、突破率详情显示"师徒加成 +5.00%"（突破率 +3.00%）。
5. **功能-边界**：徒弟数达 5 时该师父不再出现在候选列表；已有师父的弟子拜师按钮禁用。
6. **功能-死亡解绑**：师父死亡后徒弟的 masterId 清空、拜师按钮恢复可用；关系面板不再显示该师父。
7. **功能-缓存指纹**：师父大境界突破后徒弟修炼速度重算（gap 变化）；徒弟大境界突破使 gap 变化时重算。
8. **关系面板**：显示"师父"和"徒弟"分类。
9. **现有测试**：`GameTimeClockTest` 等既有单测仍通过。
10. **架构守卫**：core:domain 无新增 Room 运行时依赖；@Entity 使用符合白名单（SocialData 非 @Entity，仅为 @Embedded 组件）。
