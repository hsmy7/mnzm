package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.BattleRewardItem
import com.xianxia.sect.core.model.CultivatorCave
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillEffect
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.engine.domain.exploration.CaveExplorationSystem
import com.xianxia.sect.core.engine.domain.exploration.CaveRewardItem
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.wallet.SpiritStoneSource

/**
 * 单用户定向补偿邮件（MailService 扩展，独立文件）。
 *
 * 拆分原因：MailService 类主体接近 detekt LargeClass（800 行）阈值，
 * 补偿邮件属独立运营配置，放独立文件保持 MailService 规模稳定；
 * stateStore/mailRepo 已放宽为 internal 供本扩展读取（三重防护）。
 */
// ── 洞府探索奖励域（自 CaveExplorationProcessor 拆出，行为零变更） ────────────

private val TAG = CaveExplorationProcessor.TAG
internal fun CaveExplorationProcessor.grantBattleRewards(cave: CultivatorCave): List<BattleRewardItem> {
    val rewards = CaveExplorationSystem.generateVictoryRewards(cave)
    val battleRewardItems = mutableListOf<BattleRewardItem>()
    rewards.items.forEach { reward ->
        when (reward.type) {
            "spiritStones" -> grantSpiritStoneReward(reward, battleRewardItems)
            "equipment" -> grantEquipmentReward(reward, battleRewardItems)
            "manual" -> grantManualReward(reward, battleRewardItems)
            "pill" -> grantPillReward(reward, battleRewardItems)
        }
    }
    return battleRewardItems
}

internal fun CaveExplorationProcessor.grantSpiritStoneReward(
    reward: CaveRewardItem,
    battleRewardItems: MutableList<BattleRewardItem>
) {
    stateStore.update { spiritStoneWallet.add(this,
        amount = reward.quantity.toLong(),
        grade = SpiritStoneGrade.LOW,
        source = SpiritStoneSource.Cave
    ) }
    battleRewardItems.add(BattleRewardItem(
        itemId = reward.itemId,
        name = reward.name,
        quantity = reward.quantity,
        rarity = reward.rarity,
        type = reward.type
    ))
}

internal fun CaveExplorationProcessor.grantEquipmentReward(
    reward: CaveRewardItem,
    battleRewardItems: MutableList<BattleRewardItem>
) {
    val template = EquipmentDatabase.getById(reward.itemId)
    if (template != null) {
        val equipment = EquipmentDatabase.createFromTemplate(template).copy(
            rarity = reward.rarity,
            quantity = reward.quantity
        )
        val result = inventorySystem.withTrackingSource("cave") { inventorySystem.addEquipmentStack(equipment) }
        when (val r = result) {
            is DomainResult.Success -> battleRewardItems.add(BattleRewardItem(
                itemId = reward.itemId,
                name = reward.name,
                quantity = reward.quantity,
                rarity = reward.rarity,
                type = reward.type
            ))
            is DomainResult.Partial -> {
                battleRewardItems.add(BattleRewardItem(
                    itemId = reward.itemId,
                    name = reward.name,
                    quantity = reward.quantity,
                    rarity = reward.rarity,
                    type = reward.type
                ))
                DomainLog.w(TAG, "${reward.name} 溢出 ${r.overflow} 个")
            }
            is DomainResult.Failure -> DomainLog.w(TAG, "装备添加失败: ${r.error}")
        }
    }
}

internal fun CaveExplorationProcessor.grantManualReward(
    reward: CaveRewardItem,
    battleRewardItems: MutableList<BattleRewardItem>
) {
    val template = ManualDatabase.getById(reward.itemId)
    if (template != null) {
        val manual = ManualDatabase.createFromTemplate(template).copy(
            rarity = reward.rarity,
            quantity = reward.quantity
        )
        // 修复 P10：isSuccess 对 Partial 误判为成功——改用穷尽 when；
        // Partial 时溢出已转邮件（自动类路径），物品总量不丢失，卡片照常展示
        val result = inventorySystem.withTrackingSource("cave") {
            inventorySystem.addManualStack(manual)
        }
        when (result) {
            is DomainResult.Success -> {
                battleRewardItems.add(BattleRewardItem(
                    itemId = reward.itemId,
                    name = reward.name,
                    quantity = reward.quantity,
                    rarity = reward.rarity,
                    type = reward.type
                ))
            }
            is DomainResult.Partial -> {
                DomainLog.w(TAG, "洞府功法 ${manual.name} 溢出 ${result.overflow} 个（已转邮件）")
                battleRewardItems.add(BattleRewardItem(
                    itemId = reward.itemId,
                    name = reward.name,
                    quantity = reward.quantity,
                    rarity = reward.rarity,
                    type = reward.type
                ))
            }
            is DomainResult.Failure -> {
                DomainLog.w(TAG, "洞府功法 ${manual.name} 发放失败: ${result.error}")
            }
        }
    }
}

internal fun CaveExplorationProcessor.grantPillReward(
    reward: CaveRewardItem,
    battleRewardItems: MutableList<BattleRewardItem>
) {
    val template = PillRecipeDatabase.getRecipeById(reward.itemId)
    if (template != null) {
        val pill = buildPillFromTemplate(
            template = template,
            reward = reward
        )
        val result = inventorySystem.withTrackingSource("cave") { inventorySystem.addPill(pill) }
        when (val r = result) {
            is DomainResult.Success -> battleRewardItems.add(BattleRewardItem(
                itemId = reward.itemId,
                name = reward.name,
                quantity = reward.quantity,
                rarity = reward.rarity,
                type = reward.type
            ))
            is DomainResult.Partial -> {
                battleRewardItems.add(BattleRewardItem(
                    itemId = reward.itemId,
                    name = reward.name,
                    quantity = reward.quantity,
                    rarity = reward.rarity,
                    type = reward.type
                ))
                DomainLog.w(TAG, "${reward.name} 溢出 ${r.overflow} 个")
            }
            is DomainResult.Failure -> DomainLog.w(TAG, "丹药添加失败: ${r.error}")
        }
    }
}

/** 由丹药配方模板构建 Pill */

internal fun CaveExplorationProcessor.buildPillFromTemplate(
    template: PillRecipeDatabase.PillRecipe,
    reward: CaveRewardItem
): Pill = Pill(
    id = java.util.UUID.randomUUID().toString(),
    name = template.name,
    rarity = template.rarity,
    quantity = reward.quantity,
    description = template.description,
    category = template.category,
    effects = PillEffect(
        breakthroughChance = template.breakthroughChance,
        targetRealm = template.targetRealm,
        cultivationSpeedPercent = template.cultivationSpeedPercent,
        duration = template.duration,
        cultivationAdd = template.cultivationAdd,
        skillExpAdd = template.skillExpAdd,
        nurtureAdd = template.nurtureAdd,
        extendLife = template.extendLife,
        physicalAttackAdd = template.physicalAttackAdd,
        magicAttackAdd = template.magicAttackAdd,
        physicalDefenseAdd = template.physicalDefenseAdd,
        magicDefenseAdd = template.magicDefenseAdd,
        hpAdd = template.hpAdd,
        mpAdd = template.mpAdd,
        speedAdd = template.speedAdd,
        critRateAdd = template.critRateAdd,
        critEffectAdd = template.critEffectAdd,
        intelligenceAdd = template.intelligenceAdd,
        charmAdd = template.charmAdd,
        loyaltyAdd = template.loyaltyAdd,
        comprehensionAdd = template.comprehensionAdd,
        artifactRefiningAdd = template.artifactRefiningAdd,
        pillRefiningAdd = template.pillRefiningAdd,
        spiritPlantingAdd = template.spiritPlantingAdd,
        teachingAdd = template.teachingAdd,
        moralityAdd = template.moralityAdd
    ),
    minRealm = GameConfig.Realm.getMinRealmForRarity(template.rarity)
)
