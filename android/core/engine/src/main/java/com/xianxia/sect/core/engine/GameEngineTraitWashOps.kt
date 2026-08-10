package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.GameConfig.TraitWashType
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.RngPartition
import kotlin.coroutines.cancellation.CancellationException

// GameEngineTraitWashOps.kt — 洗炼天赋/体质/词条（玉符消耗玩法）GameEngine 扩展入口
// （对照 washSpiritRoot 的原子消耗 + sealed 结果 + 品质保底模式）
//
// 单槽语义（2026-08-09 需求变更）：洗炼只针对详情界面里指定的那一个特质
// （targetId），其余同类特质保留不动——一次只洗炼一个，不再整套重掷替换。
// 纯随机抽取函数（候选池/品阶分布/保底计数）在 GameEngineTraitWashRoll.kt。

/** 日志 TAG（与其他 GameEngine 扩展文件一致） */
private const val LOG_TAG = "GameEngine"

/** 洗炼天赋/体质/词条结果 */
sealed interface TraitWashResult {
    /** 洗炼成功：newId 为目标槽位的洗炼产物，newPityCount 为下次保底计数 */
    data class Success(val newId: String, val newPityCount: Int) : TraitWashResult
    /** 玉符不足（余额不足时不消耗随机序列） */
    data class InsufficientJadeSymbols(val current: Int, val required: Int) : TraitWashResult
    /** 其他错误（弟子不存在/非法参数/引擎异常，message 为玩家可读中文，UI 直接展示） */
    data class Error(val message: String) : TraitWashResult
}

/** 确认替换洗炼结果 */
sealed interface TraitWashConfirmResult {
    data object Success : TraitWashConfirmResult
    data class Error(val message: String) : TraitWashConfirmResult
}

/**
 * 洗炼天赋/体质/词条的**单个目标槽位**：校验弟子存在且存活 → 预检候选池 → 事务内扣 1 玉符
 * → 按 [pityCount] 保底判定抽取目标槽位的新特质（其余同类特质保留不动，由 confirm 落盘）。
 *
 * 玉符不足时提前返回且**不消耗随机序列**（随机序列确定性保持）。
 * 洗炼只返回产物不写弟子；"确认替换"由 [confirmTraitWash] 负责。
 *
 * 本地信任模型：pityCount 由 UI 洗炼会话持有，引擎仅拒绝负值、不做完整性校验
 * （单机游戏本地数据可被玩家自行修改，保底计数不作公平性凭据）。
 *
 * @param targetId 详情界面点入的目标特质 id（必须存在于弟子当前特质中）
 */
@Suppress("TooGenericExceptionCaught") // 兜底转 Error（项目范式，同 GameEngineSpiritRootOps）
suspend fun GameEngine.washTraitSlot(
    discipleId: String,
    type: TraitWashType,
    targetId: String,
    pityCount: Int
): TraitWashResult = engineContextDispatcher.withEngineContext {
    if (pityCount < 0) {
        DomainLog.w(LOG_TAG, "洗炼${type.displayName}拒绝: 非法保底计数 pity=$pityCount")
        return@withEngineContext TraitWashResult.Error("非法保底计数")
    }
    val id = discipleId.toIntOrNull()
    if (id == null) {
        DomainLog.w(LOG_TAG, "洗炼${type.displayName}拒绝: 非法弟子ID=$discipleId")
        return@withEngineContext TraitWashResult.Error("非法弟子ID")
    }
    if (targetId.isBlank()) {
        DomainLog.w(LOG_TAG, "洗炼${type.displayName}拒绝: 非法洗炼目标 id=$id")
        return@withEngineContext TraitWashResult.Error("非法洗炼目标")
    }
    try {
        val result = washSlotInner(id, type, targetId, pityCount)
        if (result is TraitWashResult.Success) {
            // 事务外刷新玉符 UI 状态（清 1Hz 节流，徽章/详情即时更新）
            jadeSymbolService.publishJadeSymbolStateNow()
        }
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.e(LOG_TAG, "洗炼${type.displayName}失败: id=$discipleId", e)
        TraitWashResult.Error("未知错误")
    }
}

/**
 * 洗炼事务内逻辑（单 update 原子完成）：存在/存活/目标校验 → 保留槽位 template 排除集 →
 * 扣费前候选预检 → 扣 1 玉符 → 按保底判定抽取。所有校验均在扣费前，失败不消耗玉符与随机序列。
 */
private fun GameEngine.washSlotInner(
    id: Int,
    type: TraitWashType,
    targetId: String,
    pityCount: Int
): TraitWashResult = stateStore.updateAndReturn {
    if (id !in discipleTables.ids) {
        DomainLog.w(LOG_TAG, "洗炼${type.displayName}拒绝: 弟子不存在 id=$id")
        return@updateAndReturn TraitWashResult.Error("弟子不存在")
    }
    // 死亡弟子拒绝洗炼（洗炼对死人无意义，防止误操作扣玉符）
    if (discipleTables.isAlive[id] != 1) {
        DomainLog.w(LOG_TAG, "洗炼${type.displayName}拒绝: 弟子已死亡 id=$id")
        return@updateAndReturn TraitWashResult.Error("弟子已死亡")
    }
    val current: Disciple = discipleTables.assemble(id)
    val currentIds = type.idsOf(current)
    // 目标特质已不存在（旧快照/已被其他操作替换）——拒绝且不扣玉符
    if (targetId !in currentIds) {
        DomainLog.w(LOG_TAG, "洗炼${type.displayName}拒绝: 目标特质已不存在 id=$id target=$targetId 当前=$currentIds")
        return@updateAndReturn TraitWashResult.Error("该特质已不存在")
    }
    // 保留槽位 template 集合：新条目不得与保留槽位冲突（否则 confirm 校验拒绝）
    val excludedTemplates = buildSet {
        for (keptId in currentIds) {
            if (keptId == targetId) continue
            type.resolveOne(keptId)?.let { add(it.template) }
        }
    }
    // 扣费前预检候选池（无随机消耗）：池全被排除 → 不扣费直接拒绝
    if (!type.hasRollCandidate(excludedTemplates)) {
        DomainLog.w(LOG_TAG, "洗炼${type.displayName}拒绝: 候选池全被排除 id=$id")
        return@updateAndReturn TraitWashResult.Error("暂无可用洗炼结果")
    }
    val required = GameConfig.TraitWash.WASH_JADE_COST
    if (!jadeSymbolService.deduct(this, required)) {
        DomainLog.w(LOG_TAG, "洗炼${type.displayName}拒绝: 玉符不足 id=$id 余额=${gameData.jadeSymbols} 需要=$required")
        return@updateAndReturn TraitWashResult.InsufficientJadeSymbols(
            current = gameData.jadeSymbols,
            required = required
        )
    }
    val roll = rollSingleTraitWash(
        gameRngManager.getRng(RngPartition.SYSTEM),
        type,
        excludedTemplates,
        pityCount
    )
    // 预检保证候选非空，newId 恒非空（null 兜底为目标不变——防御，防 Success(null) 流入 UI）
    TraitWashResult.Success(roll.newId ?: targetId, roll.newPityCount)
}

/**
 * 确认替换：把弟子目标槽位（[targetId]）替换为洗炼产物 [newId]，其余特质保留。
 *
 * 体质（cultivationSpeedBonus）与词条（CULT_SPEED）影响修炼速率——替换瞬间必须
 * checkpointDisciple 重新记账，否则 realtimeCultivation 会用旧 checkpoint 混算新速率
 * 导致跳变（与洗炼灵根确认替换同理）。
 *
 * 本地信任模型：不校验产物是否由本会话洗炼产生（任何合法 id 均可替换），
 * 单机游戏本地数据可被玩家自行修改；联网化需会话令牌绑定产物。
 *
 * @param targetId 详情界面点入的目标特质 id（必须存在于弟子当前特质中）
 * @param newId 洗炼产物 id（单个）
 */
@Suppress("TooGenericExceptionCaught") // 兜底转 Error（项目范式，同 GameEngineSpiritRootOps）
suspend fun GameEngine.confirmTraitWash(
    discipleId: String,
    type: TraitWashType,
    targetId: String,
    newId: String
): TraitWashConfirmResult = engineContextDispatcher.withEngineContext {
    val id = discipleId.toIntOrNull()
    if (id == null) {
        DomainLog.w(LOG_TAG, "确认洗炼${type.displayName}拒绝: 非法弟子ID=$discipleId")
        return@withEngineContext TraitWashConfirmResult.Error("非法弟子ID")
    }
    try {
        // 三态区分失败原因：NOT_FOUND/DEAD 给玩家明确文案（对抗性审查 2026-08-09：
        // 原实现死亡弟子确认替换只报"弟子不存在"，玩家无法判断是误点还是异常）
        val outcome = stateStore.updateAndReturn<ConfirmOutcome> {
            if (id !in discipleTables.ids) {
                DomainLog.w(LOG_TAG, "确认洗炼${type.displayName}拒绝: 弟子不存在 id=$id")
                return@updateAndReturn ConfirmOutcome.NOT_FOUND
            }
            if (discipleTables.isAlive[id] != 1) {
                DomainLog.w(LOG_TAG, "确认洗炼${type.displayName}拒绝: 弟子已死亡 id=$id")
                return@updateAndReturn ConfirmOutcome.DEAD
            }
            val current: Disciple = discipleTables.assemble(id)
            val currentIds = type.idsOf(current)
            if (!isValidSlotWash(type, currentIds, targetId, newId)) {
                DomainLog.w(
                    LOG_TAG,
                    "确认洗炼${type.displayName}拒绝: 替换校验失败 id=$id target=$targetId " +
                        "new=$newId 当前=$currentIds"
                )
                return@updateAndReturn ConfirmOutcome.INVALID
            }
            val updated = type.replaceSlot(current, targetId, newId)
            discipleTables.remove(id)
            discipleTables.insert(syncLifespanForWash(current, updated))
            // 体质/词条影响修炼速率——替换瞬间重新记账（速率投影基于 checkpoint + 新速率推导）
            discipleTables.checkpointDisciple(id, gameData.gameYear * 12 + gameData.gameMonth)
            ConfirmOutcome.REPLACED
        }
        when (outcome) {
            ConfirmOutcome.REPLACED -> TraitWashConfirmResult.Success
            ConfirmOutcome.NOT_FOUND -> TraitWashConfirmResult.Error("弟子不存在")
            ConfirmOutcome.DEAD -> TraitWashConfirmResult.Error("弟子已死亡")
            ConfirmOutcome.INVALID -> TraitWashConfirmResult.Error("该特质已不存在")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.e(LOG_TAG, "确认洗炼${type.displayName}失败: id=$discipleId", e)
        TraitWashConfirmResult.Error("未知错误")
    }
}

/**
 * 单槽替换合法性校验：目标 id 存在于当前列表、产物 id 可被 Database 解析、
 * 替换后整体 template 无重复（与生成语义一致——同一 template 的特质互斥）。
 */
private fun isValidSlotWash(
    type: TraitWashType,
    currentIds: List<String>,
    targetId: String,
    newId: String
): Boolean {
    val replaced = currentIds.map { if (it == targetId) newId else it }
    val resolved = type.resolve(replaced)
    return targetId in currentIds &&
        newId.isNotBlank() &&
        type.resolve(listOf(newId)).size == 1 &&
        resolved.size == replaced.size &&
        resolved.map { it.template }.distinct().size == resolved.size
}

/** confirm 事务内结果三态（对外映射为明确中文文案，见 [GameEngine.confirmTraitWash]） */
private enum class ConfirmOutcome { NOT_FOUND, DEAD, INVALID, REPLACED }

/**
 * 洗炼确认后同步 lifespan 到新特质加成水平（对抗性审查 2026-08-09 数据篡改者发现）。
 *
 * 背景：lifespan 出生时按 `baseLifespan * (1 + 天赋lifespan加成 + 词条lifespan加成)` 固化，
 * 突破累加只含天赋加成——天赋/词条被洗炼替换后，lifespan 携带旧加成残留（洗入"延年"不加、
 * 洗掉"延年"不减），与弟子实际特质脱节。
 *
 * 处理：按当前境界基准寿命（[GameConfig.Realm.get] maxAge）把加成差折算为年数增量。
 * 新加成高 → 寿命上调；新加成低 → 寿命下调。境界基准 maxAge 为当前寿命主分量，
 * 折算后仍由 computeMaxAge 的 max(lifespan, realmMaxAge) 兜底，不会低于境界下限。
 */
private fun syncLifespanForWash(current: Disciple, updated: Disciple): Disciple {
    // PHYSIQUE 洗炼不改 talent/affix → delta 恒为 0，天然走跳过分支，无需特判
    val base = GameConfig.Realm.get(current.realm).maxAge
    val delta = (base * (lifespanBonusOf(updated) - lifespanBonusOf(current))).toInt()
    if (delta == 0) return updated
    return updated.copy(lifespan = (updated.lifespan + delta).coerceAtLeast(1))
}

/** 天赋 + 词条的 lifespan 效果合计（与 DiscipleFactory 出生固化公式同口径） */
private fun lifespanBonusOf(disciple: Disciple): Double =
    (TalentDatabase.calculateTalentEffects(disciple.talentIds)["lifespan"] ?: 0.0) +
        (AffixDatabase.calculateAffixEffects(disciple.affixIds)["lifespan"] ?: 0.0)
