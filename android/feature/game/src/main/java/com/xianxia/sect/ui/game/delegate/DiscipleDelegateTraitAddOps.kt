package com.xianxia.sect.ui.game.delegate

import com.xianxia.sect.core.GameConfig.TraitWashType
import com.xianxia.sect.core.engine.TraitAddConfirmResult
import com.xianxia.sect.core.engine.TraitAddResult
import com.xianxia.sect.core.engine.confirmTraitAdd
import com.xianxia.sect.core.engine.rollTraitAdd

// ── 玉符新增特质族扩展（自 DiscipleDelegate 拆出，行为零变更）────────────────
// 新增天赋/体质/词条（玉符消耗玩法，流程复用洗炼界面；刷新即扣玉符、结果持久化）；
// batch-02 TooManyFunctions 收敛（类内 ≤19）外移为同包扩展，调用点语法不变。

/** 刷新天赋：扣 1 玉符 + 无负面抽取，返回产物并持久化（未确认不写弟子） */
suspend fun DiscipleDelegate.addTalent(discipleId: String): TraitAddResult =
    gameEngine.rollTraitAdd(discipleId, TraitWashType.TALENT)

/** 刷新体质：扣 1 玉符 + 无负面抽取，返回产物并持久化（未确认不写弟子） */
suspend fun DiscipleDelegate.addPhysique(discipleId: String): TraitAddResult =
    gameEngine.rollTraitAdd(discipleId, TraitWashType.PHYSIQUE)

/** 刷新词条：扣 1 玉符 + 无负面抽取，返回产物并持久化（未确认不写弟子） */
suspend fun DiscipleDelegate.addAffix(discipleId: String): TraitAddResult =
    gameEngine.rollTraitAdd(discipleId, TraitWashType.AFFIX)

/** 确认新增天赋：把刷新产物追加到弟子（不消耗玉符，清除 pending） */
suspend fun DiscipleDelegate.confirmAddTalent(discipleId: String, newId: String): TraitAddConfirmResult =
    gameEngine.confirmTraitAdd(discipleId, TraitWashType.TALENT, newId)

/** 确认新增体质：把刷新产物追加到弟子（不消耗玉符，清除 pending） */
suspend fun DiscipleDelegate.confirmAddPhysique(discipleId: String, newId: String): TraitAddConfirmResult =
    gameEngine.confirmTraitAdd(discipleId, TraitWashType.PHYSIQUE, newId)

/** 确认新增词条：把刷新产物追加到弟子（不消耗玉符，清除 pending） */
suspend fun DiscipleDelegate.confirmAddAffix(discipleId: String, newId: String): TraitAddConfirmResult =
    gameEngine.confirmTraitAdd(discipleId, TraitWashType.AFFIX, newId)
