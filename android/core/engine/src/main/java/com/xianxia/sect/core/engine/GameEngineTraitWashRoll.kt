package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.GameConfig.TraitWashType
import com.xianxia.sect.core.model.Affix
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.Physique
import com.xianxia.sect.core.model.Talent
import com.xianxia.sect.core.registry.AffixDatabase
import com.xianxia.sect.core.registry.AffixDatabase.AffixData
import com.xianxia.sect.core.registry.PhysiqueDatabase
import com.xianxia.sect.core.registry.PhysiqueDatabase.PhysiqueData
import com.xianxia.sect.core.registry.TalentDatabase
import com.xianxia.sect.core.registry.TalentDatabase.TalentData
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.asKotlinRandom

// GameEngineTraitWashRoll.kt — 洗炼天赋/体质/词条的纯随机函数（不落盘、无副作用）
//
// 单槽语义（2026-08-09 需求变更）：洗炼只针对详情界面里指定的那一个特质
// （targetId），其余同类特质保留不动——一次只洗炼一个，不再整套重掷替换。
// 本文件只含"抽取"：候选池口径、品阶分布、保底计数与排除模板过滤，
// 事务扣费与落盘在 GameEngineTraitWashOps.kt（washTraitSlot/confirmTraitWash）。

/** 单次洗炼纯随机产物（不落盘，由 UI 洗炼会话持有）；newId 可空 = 无候选（正常路径预检保证非空） */
internal data class TraitWashRoll(val newId: String?, val newPityCount: Int)

/** 洗炼引擎内部特质条目（引擎只关心 id/品阶/template，展示名由 UI 经 Database 解析） */
internal data class TraitWashEntry(val id: String, val rarity: Int, val template: String)

/**
 * 洗炼候选是否至少存在一条（无随机消耗，供扣费前预检）。
 *
 * 单槽洗炼必须先验候选后扣玉符：若候选池全被保留槽位的 template 排除
 * （现实不可达——池数百条、保留 ≤4 条），扣费后无可抽条目会白扣玉符，
 * 违反"deduct 后事务代码必须无异常/无损失路径"契约。
 */
internal fun TraitWashType.hasRollCandidate(excludedTemplates: Set<String>): Boolean = when (this) {
    TraitWashType.TALENT -> TalentDatabase.hasTalentCandidates(excludedTemplates)
    TraitWashType.PHYSIQUE -> PhysiqueDatabase.hasPhysiqueCandidates(excludedTemplates)
    TraitWashType.AFFIX -> AffixDatabase.hasAffixCandidates(excludedTemplates)
}

/**
 * 按洗炼类型单条抽取（品阶分布与生成一致；[excludedTemplates] 为保留槽位
 * template——新条目不得与保留槽位同 template，否则 confirm 校验拒绝）。
 * 无候选返回 null（调用方应先用 [hasRollCandidate] 预检）。
 */
internal fun TraitWashType.rollSingle(
    random: kotlin.random.Random,
    excludedTemplates: Set<String>
): TraitWashEntry? = when (this) {
    TraitWashType.TALENT -> TalentDatabase.rollSingleTalent(random, excludedTemplates)?.toWashEntry()
    TraitWashType.PHYSIQUE -> PhysiqueDatabase.rollSinglePhysique(random, excludedTemplates)?.toWashEntry()
    TraitWashType.AFFIX -> AffixDatabase.rollSingleAffix(random, excludedTemplates)?.toWashEntry()
}

/** 洗炼条目转内部条目（template 解析失败用 id 兜底） */
private fun Any.toWashEntry(): TraitWashEntry = when (this) {
    is Talent -> TraitWashEntry(id, rarity, TalentDatabase.getTalentDataById(id)?.template ?: id)
    is Physique -> TraitWashEntry(id, rarity, PhysiqueDatabase.getPhysiqueDataById(id)?.template ?: id)
    is Affix -> TraitWashEntry(id, rarity, AffixDatabase.getAffixDataById(id)?.template ?: id)
    else -> error("不支持的洗炼条目类型: ${this::class.simpleName}")
}

private fun TalentData.toWashEntry() = TraitWashEntry(id, rarity, template)
private fun PhysiqueData.toWashEntry() = TraitWashEntry(id, rarity, template)
private fun AffixData.toWashEntry() = TraitWashEntry(id, rarity, template)

/**
 * 从 3 阶正向池抽取一个上品条目（保底替换源）。
 *
 * [usedTemplates] 为保留槽位已占用的 template，抽取时过滤避免 template 冲突。
 * 过滤后无候选（池空/全部被占用）返回 null——**不**回退全池：回退会选出与保留槽位
 * template 重复的条目，导致产物无法通过 confirm 校验（玩家白洗 1 玉符死胡同）；
 * 调用方（rollSingleTraitWash）对 null 放弃本次抽取，保底尽力而为、下次继续累计。
 */
internal fun TraitWashType.pickTopRarity(
    random: kotlin.random.Random,
    usedTemplates: Set<String> = emptySet()
): TraitWashEntry? = pickTopByType(this, random, usedTemplates)

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

/** 解析单个 id；未知 id 返回 null */
internal fun TraitWashType.resolveOne(id: String): TraitWashEntry? = resolve(listOf(id)).firstOrNull()

/** 弟子当前持有该类型的特质 id 列表 */
internal fun TraitWashType.idsOf(disciple: Disciple): List<String> = when (this) {
    TraitWashType.TALENT -> disciple.talentIds
    TraitWashType.PHYSIQUE -> disciple.physiqueIds
    TraitWashType.AFFIX -> disciple.affixIds
}

/** 单槽替换：目标槽位 id 替换为 newId，其余槽位不变 */
internal fun TraitWashType.replaceSlot(disciple: Disciple, targetId: String, newId: String): Disciple =
    when (this) {
        TraitWashType.TALENT ->
            disciple.copy(talentIds = disciple.talentIds.map { if (it == targetId) newId else it })
        TraitWashType.PHYSIQUE ->
            disciple.copy(physiqueIds = disciple.physiqueIds.map { if (it == targetId) newId else it })
        TraitWashType.AFFIX ->
            disciple.copy(affixIds = disciple.affixIds.map { if (it == targetId) newId else it })
    }

/**
 * 单槽洗炼纯随机函数（品质保底 + 确定性）。
 *
 * 普通路径：按生成品阶分布单条抽取（排除保留槽位 template）；
 * 保底路径（[pityCount] 达到阈值）：直接从 3 阶正向池抽取（排除保留槽位 template）。
 * 两者均受 [TraitWashType.pickTopRarity]/[TraitWashType.rollSingle] 返回 null 的兜底：
 * 池空/模板全占用 → newId 为 null，调用方在扣费前已用 [TraitWashType.hasRollCandidate] 预检，
 * 正常路径不可能出现；防御语义为"放弃本次产出"。
 * 保底计数：产物含 3 阶 → 归零；否则递增（封顶在阈值，防整数域放大）。
 * 同种子 + 同 (type, excludedTemplates, pityCount) 结果完全确定。
 *
 * @param pityCount 当前保底计数（连续未出上品次数，UI 会话持有）
 * @param excludedTemplates 保留槽位 template 集合（新条目不得与之冲突）
 */
internal fun rollSingleTraitWash(
    rng: DeterministicRng,
    type: TraitWashType,
    excludedTemplates: Set<String>,
    pityCount: Int
): TraitWashRoll {
    val random = rng.asKotlinRandom()
    val forced = pityCount >= GameConfig.TraitWash.WASH_PITY_THRESHOLD
    val entry = if (forced) {
        type.pickTopRarity(random, excludedTemplates)
    } else {
        type.rollSingle(random, excludedTemplates)
    }
    val hasTop = entry?.rarity == GameConfig.TraitWash.TOP_RARITY
    // 计数防溢出：达到阈值后若一直无上品，+1 递增无意义且可被滥用放大整数域——封顶在阈值（保底判定为 >=）
    val newPityCount = if (hasTop) {
        0
    } else {
        minOf(pityCount + 1, GameConfig.TraitWash.WASH_PITY_THRESHOLD)
    }
    return TraitWashRoll(entry?.id, newPityCount)
}
