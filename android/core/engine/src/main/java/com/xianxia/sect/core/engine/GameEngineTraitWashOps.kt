package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.GameConfig.TraitWashType
import com.xianxia.sect.core.model.Affix
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.Physique
import com.xianxia.sect.core.model.Talent
import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.registry.PhysiqueDatabase
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.asKotlinRandom
import kotlin.coroutines.cancellation.CancellationException

// GameEngineTraitWashOps.kt — 洗炼天赋/体质/词条（玉符消耗玩法）GameEngine 扩展入口
// （对照 washSpiritRoot 的原子消耗 + sealed 结果 + 品质保底模式）

/** 洗炼天赋/体质/词条结果 */
sealed interface TraitWashResult {
    /** 洗炼成功：newIds 为洗炼产物（特质 id 列表），newPityCount 为下次保底计数 */
    data class Success(val newIds: List<String>, val newPityCount: Int) : TraitWashResult
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

/** 单次洗炼纯随机产物（不落盘，由 UI 洗炼会话持有） */
internal data class TraitWashRoll(val ids: List<String>, val newPityCount: Int)

/** 洗炼引擎内部特质条目（引擎只关心 id/品阶/template，展示名由 UI 经 Database 解析） */
internal data class TraitWashEntry(val id: String, val rarity: Int, val template: String)

/**
 * 按洗炼类型生成完整特质分布（= 生成弟子时的数量/品阶分布，template 去重内置）。
 *
 * draw 次数由生成逻辑决定（同种子可复现，确定性满足存档要求）。
 */
internal fun TraitWashType.generate(random: kotlin.random.Random): List<TraitWashEntry> = when (this) {
    TraitWashType.TALENT -> TalentDatabase.generateTalentsForDisciple(random).map { it.toWashEntry() }
    TraitWashType.PHYSIQUE -> PhysiqueDatabase.generateForDisciple(random).map { it.toWashEntry() }
    TraitWashType.AFFIX -> AffixDatabase.generateForDisciple(random).map { it.toWashEntry() }
}

/**
 * 从 3 阶正向池抽取一个上品条目（保底替换源）。
 *
 * [usedTemplates] 为产物其余槽位已占用的 template，抽取时过滤避免 template 冲突。
 * 过滤后无候选（池空/全部被占用）返回 null——**不**回退全池：回退会选出与其余槽位
 * template 重复的条目，导致产物无法通过 confirm 校验（玩家白洗 1 玉符死胡同）；
 * 调用方（rollTraitWash）对 null 放弃替换，保底尽力而为、下次继续累计。
 *
 * 对抗性审查（2026-08-09，边界狂魔/数据篡改者）：原实现 `candidates.ifEmpty { pool }`
 * 在空池时对空列表 `.random()` 抛 NoSuchElementException——发生在 jadeSymbolService.deduct
 * 之后的事务内，违反"deduct 后事务代码必须无异常路径"契约（玉符永久损失）；改为返回 null。
 */
internal fun TraitWashType.pickTopRarity(
    random: kotlin.random.Random,
    usedTemplates: Set<String> = emptySet()
): TraitWashEntry? = pickTopByType(this, random, usedTemplates)

/** 洗炼条目转内部条目（template 解析失败用 id 兜底） */
private fun Any.toWashEntry(): TraitWashEntry = when (this) {
    is Talent -> TraitWashEntry(id, rarity, TalentDatabase.getTalentDataById(id)?.template ?: id)
    is Physique -> TraitWashEntry(id, rarity, PhysiqueDatabase.getPhysiqueDataById(id)?.template ?: id)
    is Affix -> TraitWashEntry(id, rarity, AffixDatabase.getAffixDataById(id)?.template ?: id)
    else -> error("不支持的洗炼条目类型: ${this::class.simpleName}")
}

/** 从 3 阶正向池按已用 template 过滤后随机抽取一条；无候选返回 null（放弃替换） */
private inline fun <T : Any> pickFromPool(
    pool: List<T>,
    usedTemplates: Set<String>,
    crossinline templateOf: (T) -> String,
    random: kotlin.random.Random
): TraitWashEntry? {
    val candidates = pool.filter { templateOf(it) !in usedTemplates }
    return if (candidates.isEmpty()) null else candidates.random(random).toWashEntry()
}

private fun pickTopByType(
    type: TraitWashType,
    random: kotlin.random.Random,
    usedTemplates: Set<String>
): TraitWashEntry? = when (type) {
    TraitWashType.TALENT -> pickFromPool(
        pool = TalentDatabase.getPositiveByRarity(GameConfig.TraitWash.TOP_RARITY),
        // 过滤退役天赋类型（DEPRECATED_TALENT_TYPES），对齐生成池——保底产物空间与普通洗炼一致，
        // 退役超模条目（如 r5/r6 寿命加成）不会经保底路径重新流入（对抗性审查 2026-08-09 发现）
        usedTemplates = usedTemplates,
        templateOf = { TalentDatabase.getTalentDataById(it.id)?.template ?: it.id },
        random = random
    )
    TraitWashType.PHYSIQUE -> pickFromPool(
        pool = PhysiqueDatabase.getByRarity(GameConfig.TraitWash.TOP_RARITY).filter { !it.isNegative },
        usedTemplates = usedTemplates,
        templateOf = { PhysiqueDatabase.getPhysiqueDataById(it.id)?.template ?: it.id },
        random = random
    )
    TraitWashType.AFFIX -> pickFromPool(
        pool = AffixDatabase.getByRarity(GameConfig.TraitWash.TOP_RARITY).filter { !it.isNegative },
        usedTemplates = usedTemplates,
        templateOf = { AffixDatabase.getAffixDataById(it.id)?.template ?: it.id },
        random = random
    )
}

/**
 * 把 id 列表解析为洗炼条目（confirm 校验与合法性判定用；未知 id 会被 [getXByIds] 丢弃）。
 */
internal fun TraitWashType.resolve(ids: List<String>): List<TraitWashEntry> = when (this) {
    TraitWashType.TALENT -> TalentDatabase.getTalentsByIds(ids).map { it.toWashEntry() }
    TraitWashType.PHYSIQUE -> PhysiqueDatabase.getPhysiquesByIds(ids).map { it.toWashEntry() }
    TraitWashType.AFFIX -> AffixDatabase.getAffixesByIds(ids).map { it.toWashEntry() }
}

/**
 * 洗炼天赋/体质/词条纯随机函数（品质保底 + 确定性）。
 *
 * 普通路径：按生成分布抽取；保底路径（[pityCount] 达到阈值且结果无 3 阶）：
 * 替换第一个非 3 阶槽位为 3 阶池抽取，结果为空时直接出 1 个 3 阶
 * （两者均受 [TraitWashType.pickTopRarity] 返回 null 的兜底：池空/模板全占用 → 放弃本次保底）。
 * 保底计数：结果含 3 阶 → 归零；否则递增（封顶在阈值，防整数域放大）。
 * 同种子 + 同 (type, pityCount) 结果完全确定。
 *
 * @param pityCount 当前保底计数（连续未出上品次数，UI 会话持有）
 */
internal fun rollTraitWash(rng: DeterministicRng, type: TraitWashType, pityCount: Int): TraitWashRoll {
    val random = rng.asKotlinRandom()
    val generated = type.generate(random)
    val forced = pityCount >= GameConfig.TraitWash.WASH_PITY_THRESHOLD
    val entries = if (forced && generated.none { it.rarity == GameConfig.TraitWash.TOP_RARITY }) {
        applyPityForced(type, random, generated)
    } else {
        generated
    }
    val hasTop = entries.any { it.rarity == GameConfig.TraitWash.TOP_RARITY }
    // 计数防溢出：达到阈值后若一直无上品，+1 递增无意义且可被滥用放大整数域——封顶在阈值（保底判定为 >=）
    val newPityCount = if (hasTop) {
        0
    } else {
        minOf(pityCount + 1, GameConfig.TraitWash.WASH_PITY_THRESHOLD)
    }
    return TraitWashRoll(entries.map { it.id }, newPityCount)
}

/**
 * 保底替换：空产物直接出 1 个 3 阶；否则替换第一个非 3 阶槽位。
 * 两者均受 [TraitWashType.pickTopRarity] null 兜底——保底池无可用候选
 * （全部 template 被占用/池空）→ 放弃替换、保留原产物，保底计数继续累计。
 */
private fun applyPityForced(
    type: TraitWashType,
    random: kotlin.random.Random,
    generated: List<TraitWashEntry>
): List<TraitWashEntry> {
    if (generated.isEmpty()) {
        return listOfNotNull(type.pickTopRarity(random))
    }
    val replaceIndex = generated.indexOfFirst { it.rarity != GameConfig.TraitWash.TOP_RARITY }
    val usedTemplates = generated.filterIndexed { index, _ -> index != replaceIndex }
        .map { it.template }.toSet()
    return generated.toMutableList().apply {
        type.pickTopRarity(random, usedTemplates)?.let { set(replaceIndex, it) }
    }
}

/**
 * 洗炼天赋/体质/词条：校验弟子存在且存活 → 事务内扣 1 玉符 → 按 [pityCount] 保底判定抽取。
 *
 * 玉符不足时提前返回且**不消耗随机序列**（随机序列确定性保持）。
 * 洗炼只返回产物不写弟子；"确认替换"由 [confirmTraitWash] 负责。
 *
 * 本地信任模型：pityCount 由 UI 洗炼会话持有，引擎仅拒绝负值、不做完整性校验
 * （单机游戏本地数据可被玩家自行修改，保底计数不作公平性凭据）。
 */
@Suppress("TooGenericExceptionCaught") // 兜底转 Error（项目范式，同 GameEngineSpiritRootOps）
suspend fun GameEngine.washTrait(
    discipleId: String,
    type: TraitWashType,
    pityCount: Int
): TraitWashResult = engineContextDispatcher.withEngineContext {
    if (pityCount < 0) {
        return@withEngineContext TraitWashResult.Error("非法保底计数")
    }
    val id = discipleId.toIntOrNull()
    if (id == null) {
        return@withEngineContext TraitWashResult.Error("非法弟子ID")
    }
    try {
        val required = GameConfig.TraitWash.WASH_JADE_COST
        val result = stateStore.updateAndReturn {
            if (id !in discipleTables.ids) {
                return@updateAndReturn TraitWashResult.Error("弟子不存在")
            }
            // 死亡弟子拒绝洗炼（洗炼对死人无意义，防止误操作扣玉符）
            if (discipleTables.isAlive[id] != 1) {
                return@updateAndReturn TraitWashResult.Error("弟子已死亡")
            }
            if (!jadeSymbolService.deduct(this, required)) {
                return@updateAndReturn TraitWashResult.InsufficientJadeSymbols(
                    current = gameData.jadeSymbols,
                    required = required
                )
            }
            val roll = rollTraitWash(
                gameRngManager.getRng(RngPartition.SYSTEM),
                type,
                pityCount
            )
            TraitWashResult.Success(roll.ids, roll.newPityCount)
        }
        if (result is TraitWashResult.Success) {
            // 事务外刷新玉符 UI 状态（清 1Hz 节流，徽章/详情即时更新）
            jadeSymbolService.publishJadeSymbolStateNow()
        }
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.e("GameEngine", "洗炼${type.displayName}失败: id=$discipleId", e)
        TraitWashResult.Error("未知错误")
    }
}

/**
 * 确认替换：把弟子对应特质列表替换为洗炼产物（同事务 remove + insert）。
 *
 * 体质（cultivationSpeedBonus）与词条（CULT_SPEED）影响修炼速率——替换瞬间必须
 * checkpointDisciple 重新记账，否则 realtimeCultivation 会用旧 checkpoint 混算新速率
 * 导致跳变（与洗炼灵根确认替换同理）。
 *
 * 本地信任模型：不校验产物是否由本会话洗炼产生（任何合法 id 列表均可替换），
 * 单机游戏本地数据可被玩家自行修改；联网化需会话令牌绑定产物。
 */
@Suppress("TooGenericExceptionCaught") // 兜底转 Error（项目范式，同 GameEngineSpiritRootOps）
suspend fun GameEngine.confirmTraitWash(
    discipleId: String,
    type: TraitWashType,
    newIds: List<String>
): TraitWashConfirmResult = engineContextDispatcher.withEngineContext {
    if (!isValidWashedTraits(type, newIds)) {
        return@withEngineContext TraitWashConfirmResult.Error("非法洗炼数据")
    }
    val id = discipleId.toIntOrNull()
    if (id == null) {
        return@withEngineContext TraitWashConfirmResult.Error("非法弟子ID")
    }
    try {
        // 三态区分失败原因：NOT_FOUND/DEAD 给玩家明确文案（对抗性审查 2026-08-09：
        // 原实现死亡弟子确认替换只报"弟子不存在"，玩家无法判断是误点还是异常）
        val outcome = stateStore.updateAndReturn<ConfirmOutcome> {
            if (id !in discipleTables.ids) return@updateAndReturn ConfirmOutcome.NOT_FOUND
            if (discipleTables.isAlive[id] != 1) return@updateAndReturn ConfirmOutcome.DEAD
            val current: Disciple = discipleTables.assemble(id)
            val updated = when (type) {
                TraitWashType.TALENT -> current.copy(talentIds = newIds)
                TraitWashType.PHYSIQUE -> current.copy(physiqueIds = newIds)
                TraitWashType.AFFIX -> current.copy(affixIds = newIds)
            }
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
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.e("GameEngine", "确认洗炼${type.displayName}失败: id=$discipleId", e)
        TraitWashConfirmResult.Error("未知错误")
    }
}

/**
 * 洗炼产物合法性校验：数量 ≤ 上限、全部 id 可被对应 Database 解析、template 无重复。
 * （空列表允许——洗炼可能掷出 0 个特质，与生成语义一致，避免"无特质死胡同付费"体验。）
 */
private fun isValidWashedTraits(type: TraitWashType, newIds: List<String>): Boolean {
    val resolved = type.resolve(newIds)
    val countOk = newIds.size <= GameConfig.TraitWash.MAX_TRAIT_COUNT && resolved.size == newIds.size
    return countOk && resolved.map { it.template }.distinct().size == resolved.size
}

/** confirm 事务内结果三态（对外映射为明确中文文案，见 [GameEngine.confirmTraitWash]） */
private enum class ConfirmOutcome { NOT_FOUND, DEAD, REPLACED }

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
