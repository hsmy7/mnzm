# 修复：战胜AI宗门后玩家宗门涌入1000+弟子

## Summary

玩家战胜并占领AI宗门后，玩家宗门会涌入1000多名莫名弟子。根因有三：
1. AI宗门弟子年度招募**无截断累积**，多年后远超上限，占领时全部涌入 `recruitList`。
2. 完整性检查 `checkAndRepairAiSectDisciples` **不跳过已占领宗门**，占领后清空的弟子池被重新填充50人。
3. `attackSect` **不检查 `isPlayerOccupied`**，可反复攻击已占领宗门，每次读档后重刷的弟子被再次俘虏。

设计意图（用户确认，保持不变）：俘虏全部弟子进入 `recruitList`（非直接入宗门），自动招募遵守每月上限（30人/月），已占领宗门继续每年产生新弟子流入 `recruitList`。

## Current State Analysis

### Bug 触发路径

**路径A（主因，单次涌入1000+）**：
- [CaveExplorationProcessor.kt:678](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\service\CaveExplorationProcessor.kt#L678) `else` 分支：普通AI宗门 `updatedAiDisciples[sectId] = disciples + newRecruits`，**无截断**。`generateYearlyRecruits` 每年产0-6人，多年游戏后累积可远超 `MAX_AI_DISCIPLES_PER_SECT`(1000)。
- [CaveExplorationProcessor.kt:675](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\service\CaveExplorationProcessor.kt#L675) `occupierSectId` 分支：AI占领宗门的占领者池 `occupierDisciples + newRecruits`，同样**无截断**。
- 存在带截断的 `recruitYearlyDisciples`（[AISectDiscipleManager.kt:360-371](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\domain\diplomacy\AISectDiscipleManager.kt#L360-L371)，截到1000）但 `processSectDisciplesYearlyRecruitment` 未调用它。
- 占领时 [GameEngineBattleOps.kt:145](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\GameEngineBattleOps.kt#L145) `capturedDisciples = data.aiSectDisciples[sectId]?.filter { it.isAlive }` 取整个池子，[行165](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\GameEngineBattleOps.kt#L165) 全部追加到 `recruitList`。`prisonerSpiritRootFilter` 默认空 → 全接收。

**路径B（持续泄漏）**：
- [GameEngineCoordination.kt:136](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\GameEngineCoordination.kt#L136) `checkAndRepairAiSectDisciples` 只跳过 `isPlayerSect`，不跳过 `isPlayerOccupied`。占领后 `aiSectDisciples[sectId]` 被清空（[行166](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\GameEngineBattleOps.kt#L166)），下次完整性检查重新填充50人。

**路径C（可重复刷）**：
- [GameEngineBattleOps.kt:44](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\GameEngineBattleOps.kt#L44) `attackSect` 只检查 `isPlayerSect` 和附属关系，不检查 `isPlayerOccupied`。路径B重刷50人后，玩家可再次攻击同一已占领宗门，再次俘虏。

### 已符合设计意图（不改）
- 俘虏进入 `recruitList`：[行165](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\GameEngineBattleOps.kt#L165) ✓
- 自动招募月上限：[RecruitService.kt:77-152](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\service\RecruitService.kt#L77-L152) `RECRUIT_MONTHLY_LIMIT=30` ✓
- `recruitAllFromList` 月上限：[GameEngineCoordination.kt:561-613](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\GameEngineCoordination.kt#L561-L613) ✓
- 已占领宗门继续产弟子流入 recruitList：[CaveExplorationProcessor.kt:670-672](file:///c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\service\CaveExplorationProcessor.kt#L670-L672) ✓（用户确认保留）

## Proposed Changes

### Change 1：AI宗门年度招募添加截断

**文件**：`c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\domain\diplomacy\AISectDiscipleManager.kt`

**What**：新增 `truncateToLimit` 公开函数，复用 `recruitYearlyDisciples` 中已有的截断逻辑（按战力降序取前 `MAX_AI_DISCIPLES_PER_SECT` 个），供 `CaveExplorationProcessor` 调用。同时让 `recruitYearlyDisciples` 内部复用该函数，消除重复。

```kotlin
fun truncateToLimit(disciples: List<Disciple>): List<Disciple> =
    if (disciples.size > PlantSlotData.MAX_AI_DISCIPLES_PER_SECT) {
        disciples.sortedByDescending {
            it.combat.basePhysicalAttack + it.combat.baseMagicAttack + it.combat.baseHp
        }.take(PlantSlotData.MAX_AI_DISCIPLES_PER_SECT)
    } else {
        disciples
    }
```

`recruitYearlyDisciples` 改为 `return truncateToLimit(existingDisciples + newDisciples)`。

**文件**：`c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\service\CaveExplorationProcessor.kt`

**What**：在 `processSectDisciplesYearlyRecruitment`（行659-696）的两个无截断分支套用 `truncateToLimit`：

- 行675 `occupierSectId` 分支：
  ```kotlin
  updatedAiDisciples[sect.occupierSectId] =
      AISectDiscipleManager.truncateToLimit(occupierDisciples + newRecruits)
  ```
- 行678 `else` 分支：
  ```kotlin
  updatedAiDisciples[sectId] =
      AISectDiscipleManager.truncateToLimit(disciples + newRecruits)
  ```

`isPlayerOccupied` 分支（行671）不改——其新弟子流入 `recruitList`，由月上限控制，无需截断。

**Why**：堵住路径A根因。AI宗门弟子池封顶 `MAX_AI_DISCIPLES_PER_SECT`(1000)，占领时俘虏数量有界。

### Change 2：完整性检查跳过已占领宗门

**文件**：`c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\GameEngineCoordination.kt`

**What**：`checkAndRepairAiSectDisciples`（行128-157）行136 后追加：
```kotlin
if (sect.isPlayerSect) continue
if (sect.isPlayerOccupied) continue   // 新增：已占领宗门不重刷弟子池
```

**Why**：堵住路径B。占领后弟子池应保持空（新弟子由 `processSectDisciplesYearlyRecruitment` 的 `isPlayerOccupied` 分支直接产入 recruitList），完整性检查不应回填。

### Change 3：禁止攻击已占领宗门

**文件**：`c:\Mnzm\XianxiaSectNative\android\core\engine\src\main\java\com\xianxia\sect\core\GameEngineBattleOps.kt`

**What**：`attackSect` 行44 后追加：
```kotlin
if (sectId.isBlank() || targetSect.isPlayerSect) return@withEngineContext
// 新增：已由玩家占领的宗门不可再次攻击
if (targetSect.isPlayerOccupied) return@withEngineContext
```

**Why**：堵住路径C。已占领宗门不应可重复攻击俘虏。

## Assumptions & Decisions

1. **俘虏范围=全部**（用户确认）：保持 `capturedDisciples` 取整个池子、`prisonerSpiritRootFilter` 默认空全接收的行为不变。数量由 Change 1 截断后有界。
2. **占领后继续产弟子**（用户确认）：`isPlayerOccupied` 分支年度产生0-6人入 recruitList 的行为不变，由月上限30人/月控制入宗门速率。
3. **截断阈值**：复用既有常量 `MAX_AI_DISCIPLES_PER_SECT = 1000`，与 `recruitYearlyDisciples` 一致，不引入新常量。
4. **截断策略**：按战力降序保留强者，与 `recruitYearlyDisciples` 既有策略一致。
5. **不改** `recruitList` 容量、`addDisciple` 上限、`prisonerSpiritRootFilter` 默认值——月上限机制已足够控制入宗门速率。

## Verification

1. **编译**：`./gradlew :android:core:engine:compileDebugKotlin`（或项目对应编译任务）通过。
2. **单元测试**：若 `AISectDiscipleManager` / `CaveExplorationProcessor` 存在测试，运行确认 `truncateToLimit` 行为与 `recruitYearlyDisciples` 截断一致。
3. **场景验证（手动/集成）**：
   - 构造一个AI宗门弟子数 >1000 的存档（模拟多年累积），占领后确认 `recruitList` 仅增加 ≤1000 人。
   - 占领后读档重启，确认 `checkAndRepairAiSectDisciples` 不回填已占领宗门弟子池（日志无"填充至50人"针对该宗门）。
   - 占领后再次调用 `attackSect` 攻击同一宗门，确认被拦截（无俘虏、无战斗）。
   - 占领后跨年，确认 `recruitList` 仅增加0-6人/年/宗门，自动招募不超过30人/月。
