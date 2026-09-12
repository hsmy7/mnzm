package com.xianxia.sect.core.engine


import com.xianxia.sect.core.util.ItemNames
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.domain.battle.Battle
import com.xianxia.sect.core.engine.domain.battle.WarRewards
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.model.Rarity

// ── 战利品发放/战果 UI 簇（attackSect 与世界关卡胜负结算共享；跨文件调用面为 internal）──


/** 胜利结算 UI 结果（attackSect 提取） */
internal fun GameEngine.applyVictoryPendingResult(
    log: BattleLog,
    teamMembers: List<BattleLogMember>,
    rewards: WarRewards
) {
    stateStore.setPendingBattleResult(BattleResultUIData(battleLogId = log.id, victory = true,
        teamMembers = teamMembers, rewards = warRewardsToBattleRewardItems(rewards)))
    stateStore.setPendingBattleRewardCards(buildBattleRewardCards(rewards))
}

/** 失败结算 UI 结果（attackSect 提取） */
internal fun GameEngine.applyDefeatPendingResult(log: BattleLog, teamMembers: List<BattleLogMember>) {
    stateStore.setPendingBattleResult(BattleResultUIData(battleLogId = log.id, victory = false,
        teamMembers = teamMembers, rewards = emptyList()))
}

    /**
     * 发放宗门战奖励（灵石 + 装备/功法/丹药/草药/材料/种子六类）。
     * 在调用方 stateStore.update 事务内执行，保持原子性。
     */
    internal fun GameEngine.grantWarRewardsInside(state: MutableGameState, rewards: WarRewards) {
        spiritStoneWallet.add(state, rewards.spiritStones,
            SpiritStoneGrade.LOW, SpiritStoneSource.Battle)
        inventorySystem.withTrackingSource("battle") {
            rewards.equipmentStacks.forEach { item ->
                grantStackResult(item.name, inventorySystem.addEquipmentStack(item))
            }
        }
        rewards.manualStacks.forEach { item ->
            grantStackResult(item.name, inventorySystem.addManualStack(item))
        }
        inventorySystem.withTrackingSource("battle") {
            rewards.pills.forEach { item ->
                grantStackResult(item.name, inventorySystem.addPill(item))
            }
            rewards.herbs.forEach { item ->
                grantStackResult(item.name, inventorySystem.addHerb(item))
            }
        }
        rewards.materials.forEach { item ->
            grantStackResult(item.name, inventorySystem.addMaterial(item))
        }
        rewards.seeds.forEach { item ->
            grantStackResult(item.name, inventorySystem.addSeed(item))
        }
    }

    /** 物品入库结果统一日志（成功静默/部分溢出/失败告警） */
    private fun GameEngine.grantStackResult(name: String, result: DomainResult<*>) {
        when (result) {
            is DomainResult.Success -> {}
            is DomainResult.Partial -> DomainLog.w("GameEngine", "$name 溢出 ${result.overflow} 个")
            is DomainResult.Failure -> DomainLog.w("GameEngine", "添加 $name 失败: ${result.error}")
        }
    }


    private fun GameEngine.buildBattleRewardCards(rewards: WarRewards): List<RewardCardItem> {
        val cards = mutableListOf<RewardCardItem>()
        if (rewards.spiritStones > 0) {
            cards.add(RewardCardItem(itemName = ItemNames.SPIRIT_STONE, itemType = "spiritStones",
                rarity = Rarity.COMMON.toInt(), quantity = rewards.spiritStones.toInt()))
        }
        rewards.equipmentStacks.forEach { cards.add(RewardCardItem(itemName = it.name, itemType = "equipment",
            rarity = it.rarity, quantity = it.quantity)) }
        rewards.manualStacks.forEach { cards.add(RewardCardItem(itemName = it.name, itemType = "manual",
            rarity = it.rarity, quantity = it.quantity)) }
        rewards.pills.forEach { cards.add(RewardCardItem(itemName = it.name, itemType = "pill", rarity = it.rarity,
            quantity = it.quantity)) }
        rewards.materials.forEach { cards.add(RewardCardItem(itemName = it.name, itemType = "material",
            rarity = it.rarity, quantity = it.quantity)) }
        rewards.herbs.forEach { cards.add(RewardCardItem(itemName = it.name, itemType = "herb", rarity = it.rarity,
            quantity = it.quantity)) }
        rewards.seeds.forEach { cards.add(RewardCardItem(itemName = it.name, itemType = "seed", rarity = it.rarity,
            quantity = it.quantity)) }
        return cards
    }
