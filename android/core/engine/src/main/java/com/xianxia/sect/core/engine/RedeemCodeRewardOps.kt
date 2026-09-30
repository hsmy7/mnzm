package com.xianxia.sect.core.engine

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.engine.domain.EquipmentFactory
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.model.CharacterTemplateDb
import com.xianxia.sect.core.model.RedeemRewardType
import com.xianxia.sect.core.model.RewardSelectedItem

// ── 兑换码奖励生成域（自 RedeemCodeManager 拆出） ────────────────────────────

/**
 * 角色碎片奖励的条目类型字面量。
 *
 * 与 [RedeemRewardType.FRAGMENT] 的小写形式同值：奖励条目以字符串携带类型，
 * 服务侧（`RedeemCodeService`）据此把碎片从物品发放循环中摘出来单独入账。
 */
internal const val REWARD_TYPE_FRAGMENT = "fragment"

private val TAG = RedeemCodeManager.TAG

/** 物品类奖励生成：EQUIPMENT/MANUAL/PILL/MATERIAL/HERB/SEED 分支 */
internal fun RedeemCodeManager.addItemRewards(
    type: RedeemRewardType,
    rarity: Int,
    quantity: Int,
    random: kotlin.random.Random,
    rewards: MutableList<RewardSelectedItem>
) {
    // quantity 负数时 repeat 零次迭代 →
    // 零奖励但兑换码照常消耗（静默吞码）；coerceAtLeast(1) 兜底
    repeat(quantity.coerceAtLeast(1)) {
        val (id, name, rarity) = generateSingleItemReward(type, rarity, random)
        rewards.add(
            RewardSelectedItem(
                id = id,
                type = type.name.lowercase(),
                name = name,
                rarity = rarity,
                quantity = 1
            )
        )
    }
    DomainLog.d(
        TAG,
        "Generated $quantity ${type.name.lowercase()}(s) with rarity $rarity"
    )
}

/**
 * 角色碎片奖励生成：FRAGMENT 分支。
 *
 * 只产出奖励条目，**不产生弟子、不写碎片账本**——碎片的入账由调用方
 * （`RedeemCodeService`）在兑换码确认消耗之后交寻访域碎片门面完成，
 * 本文件不触碰 `gachaFragmentCounts`（弟子与碎片的唯一写入方分别在
 * 模板实例化链与寻访域）。
 *
 * @param templateId 角色模板 id，取值域见 [CharacterTemplateDb]；null 或未知均视为配置错误
 * @param quantity 碎片数量（≤0 时按 1 发放，与物品类奖励的兜底口径一致）
 * @param rewards 奖励条目累加容器，条目 `id` 即 templateId，供服务侧回查
 * @return true=条目已生成；false=模板不存在，该条奖励被拒绝（记日志，不抛异常）
 */
internal fun RedeemCodeManager.addFragmentRewards(
    templateId: String?,
    quantity: Int,
    rewards: MutableList<RewardSelectedItem>
): Boolean {
    val template = templateId?.let { CharacterTemplateDb.byId(it) }
    if (template == null) {
        DomainLog.w(TAG, "Refused fragment reward: unknown character template, templateId=$templateId")
        return false
    }
    rewards.add(
        RewardSelectedItem(
            id = template.id,
            type = REWARD_TYPE_FRAGMENT,
            name = template.name,
            rarity = 1,
            quantity = quantity.coerceAtLeast(1)
        )
    )
    DomainLog.d(TAG, "Generated fragment reward: template=${template.id}, quantity=$quantity")
    return true
}

/** 功法包奖励生成：MANUAL_PACK 分支 */

internal fun RedeemCodeManager.addManualPackRewards(
    random: kotlin.random.Random,
    rewards: MutableList<RewardSelectedItem>
) {
    val rarities = listOf(1, 2, 3, 4)
    rarities.forEach { targetRarity ->
        val templates = ManualDatabase.getByRarity(targetRarity)
        repeat(30) {
            val template = templates[random.nextInt(templates.size)]
            val manual = ManualDatabase.createFromTemplate(template)
            rewards.add(
                RewardSelectedItem(
                    id = manual.id,
                    type = "manual",
                    name = manual.name,
                    rarity = manual.rarity,
                    quantity = 1
                )
            )
        }
    }
    DomainLog.d(TAG, "Generated manual pack: 30 manuals for each rarity 1-4")
}

internal fun RedeemCodeManager.generateRandomEquipment(rarity: Int,
    random: kotlin.random.Random): EquipmentInstance {
    // B3：装备产出唯一入口 EquipmentFactory（品阶指定、套装二选一）
    val setId = if (random.nextBoolean()) "lietian" else "zifu"
    return EquipmentFactory.create(setId, EquipmentFactory.pickPart(setId, random), rarity, random)
}

/**
 * 单物品类奖励生成（统一 6 种物品类型的生成器分派）。
 *
 * @return Triple(id, name, rarity)——RewardSelectedItem 构造所需字段
 */

internal fun RedeemCodeManager.generateSingleItemReward(
    type: RedeemRewardType,
    rarity: Int,
    random: kotlin.random.Random
): Triple<String, String, Int> = when (type) {
    RedeemRewardType.EQUIPMENT -> {
        val equipment = generateRandomEquipment(rarity, random)
        Triple(equipment.id, equipment.name, equipment.rarity)
    }
    RedeemRewardType.MANUAL -> {
        val manual = ManualDatabase.generateRandom(minRarity = rarity, maxRarity = rarity, random = random)
        Triple(manual.id, manual.name, manual.rarity)
    }
    RedeemRewardType.PILL -> {
        val pill = ItemDatabase.generateRandomPill(minRarity = rarity, maxRarity = rarity, random = random)
        Triple(pill.id, pill.name, pill.rarity)
    }
    RedeemRewardType.MATERIAL -> {
        val material = ItemDatabase.generateRandomMaterial(minRarity = rarity, maxRarity = rarity, random = random)
        Triple(material.id, material.name, material.rarity)
    }
    RedeemRewardType.HERB -> {
        val herb = HerbDatabase.generateRandomHerb(minRarity = rarity, maxRarity = rarity, random = random)
        Triple(herb.id, herb.name, herb.rarity)
    }
    RedeemRewardType.SEED -> {
        val seed = HerbDatabase.generateRandomSeed(minRarity = rarity, maxRarity = rarity, random = random)
        Triple(seed.id, seed.name, seed.rarity)
    }
    else -> error("generateSingleItemReward 仅支持物品类奖励，实际: $type")
}
