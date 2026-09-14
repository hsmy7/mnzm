package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.GameConfig.TraitWashType
import com.xianxia.sect.core.engine.BreakthroughBonusResult
import com.xianxia.sect.core.engine.SpiritRootWashConfirmResult
import com.xianxia.sect.core.engine.SpiritRootWashResult
import com.xianxia.sect.core.engine.TraitWashConfirmResult
import com.xianxia.sect.core.engine.TraitWashResult
import com.xianxia.sect.core.engine.confirmSpiritRootWash
import com.xianxia.sect.core.engine.confirmTraitWash
import com.xianxia.sect.core.engine.purchaseBreakthroughBonus
import com.xianxia.sect.core.engine.washSpiritRoot
import com.xianxia.sect.core.engine.washTraitSlot

// ── 玉符洗炼族扩展（自 DiscipleDelegate 拆出，行为零变更）────────────────────
// 洗炼灵根/天赋/体质/词条 + 突破玉符：流程对齐（扣玉符 → 保底判定抽取 →
// UI 会话持有产物，未确认不写弟子）；batch-02 TooManyFunctions 收敛
// （类内 ≤19）外移为同包扩展，调用点语法不变。

/** 洗炼灵根：扣 1 玉符 + 保底判定抽取，返回产物（UI 会话持有结果，未确认不写弟子） */
suspend fun DiscipleDelegate.washSpiritRoot(discipleId: String, pityCount: Int): SpiritRootWashResult =
    gameEngine.washSpiritRoot(discipleId, pityCount)

/** 确认替换：把弟子灵根替换为洗炼产物 */
suspend fun DiscipleDelegate.confirmSpiritRootWash(discipleId: String, newRootType: String): SpiritRootWashConfirmResult
    =
    gameEngine.confirmSpiritRootWash(discipleId, newRootType)

/** 消耗 1 玉符提高弟子突破率（上限 0.30 即最多 2 次；突破尝试后自动清除重置） */
suspend fun DiscipleDelegate.purchaseBreakthroughBonus(discipleId: String): BreakthroughBonusResult =
    gameEngine.purchaseBreakthroughBonus(discipleId)

/**
 * 洗炼天赋的单个目标槽位：扣 1 玉符 + 保底判定抽取，返回产物
 * （UI 会话持有结果，未确认不写弟子；其余天赋保留不动）。
 */
suspend fun DiscipleDelegate.washTalent(discipleId: String, targetId: String, pityCount: Int): TraitWashResult =
    gameEngine.washTraitSlot(discipleId, TraitWashType.TALENT, targetId, pityCount)

/** 洗炼体质的单个目标槽位：扣 1 玉符 + 保底判定抽取，返回产物 */
suspend fun DiscipleDelegate.washPhysique(discipleId: String, targetId: String, pityCount: Int): TraitWashResult =
    gameEngine.washTraitSlot(discipleId, TraitWashType.PHYSIQUE, targetId, pityCount)

/** 洗炼词条的单个目标槽位：扣 1 玉符 + 保底判定抽取，返回产物 */
suspend fun DiscipleDelegate.washAffix(discipleId: String, targetId: String, pityCount: Int): TraitWashResult =
    gameEngine.washTraitSlot(discipleId, TraitWashType.AFFIX, targetId, pityCount)

/** 确认替换天赋：把目标天赋槽位替换为洗炼产物（其余天赋保留） */
suspend fun DiscipleDelegate.confirmTalent(discipleId: String, targetId: String, newId: String): TraitWashConfirmResult
    =
    gameEngine.confirmTraitWash(discipleId, TraitWashType.TALENT, targetId, newId)

/** 确认替换体质：把目标体质槽位替换为洗炼产物（其余体质保留） */
suspend fun DiscipleDelegate.confirmPhysique(discipleId: String, targetId: String, newId: String):
    TraitWashConfirmResult =
    gameEngine.confirmTraitWash(discipleId, TraitWashType.PHYSIQUE, targetId, newId)

/** 确认替换词条：把目标词条槽位替换为洗炼产物（其余词条保留） */
suspend fun DiscipleDelegate.confirmAffix(discipleId: String, targetId: String, newId: String): TraitWashConfirmResult =
    gameEngine.confirmTraitWash(discipleId, TraitWashType.AFFIX, targetId, newId)
