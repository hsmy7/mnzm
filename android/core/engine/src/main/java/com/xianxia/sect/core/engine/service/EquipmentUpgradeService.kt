package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.EquipmentLevelSystem
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.model.EquipAffixSet
import com.xianxia.sect.core.model.EquipLevelCurve
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 装备升级/分解事务（装备重构 B3，方案 §3.6/§3.9；替换孕养体系 R4）。
 *
 * ## 升级（[upgradeEquipment]）
 * 每次升 1 级：扣灵石（`100 × rarity² × level`）+ 兽材（`max(1, level/10)` 件）；
 * 经验由消耗直接转化（升级材料为主动升级唯一路径——战斗胜利 +10% 经验的
 * 既有链路在本批退役，升级只由玩家交互驱动）；升级动作完成时判定
 * `newLevel % 3 == 0` 触发一次副词条强化（随机词条 +1 次，走
 * `RngPartition.EQUIPMENT`）。材料不足/已满级失败且**不扣材料**。
 *
 * ## 分解（[dismantleEquipment]）
 * 返还累计升级消耗 × 50%（灵石向下取整 + 兽材向下取整件数）；`isLocked`
 * 或已穿戴拒分解。分解是装备唯一的"消耗汇"（rules/economy-design.md）。
 *
 * ## 兽材口径（B3 落地，B4 校准）
 * 消耗/返还的"兽材"= **任意 BEAST_* 类目材料合并计数**，按 (rarity 升序,
 * id 升序) 逐堆叠扣减/返还（返还铸入最低稀有度堆叠语义 = 新独立条目）。
 * 双端同口径（C++ equipment_tx 同排序）；B4 数值对齐批可按经济基线微调。
 */
@GameService("EquipmentUpgradeService")
@Singleton
class EquipmentUpgradeService @Inject constructor(
    private val stateStore: GameStateStore,
    private val rngManager: GameRngManager
) {
    companion object {
        private const val TAG = "EquipmentUpgradeService"
    }

    // ==================== 升级 ====================

    /** 升级一件装备 1 级（材料校验 → 扣材料 → 推进 → 强化节点） */
    fun upgradeEquipment(equipmentId: String): DomainResult<EquipmentInstance> {
        var error: AppError.Domain? = AppError.Domain.Disciple.NotFound(equipmentId)
        var upgraded: EquipmentInstance? = null
        stateStore.update {
            val (err, result) = upgradeInTransaction(equipmentId)
            error = err
            upgraded = result
        }
        val final = upgraded
        val finalError = error
        return if (finalError == null && final != null) DomainResult.Success(final)
        else DomainResult.Failure(finalError ?: AppError.Domain.Disciple.NotFound(equipmentId))
    }

    /** 升级事务主体（返回错误或升级后实例） */
    @Suppress("ReturnCount")
    private fun MutableGameState.upgradeInTransaction(
        equipmentId: String
    ): Pair<AppError.Domain?, EquipmentInstance?> {
        val instance = equipmentInstances.get(equipmentId)
            ?: return AppError.Domain.Disciple.NotFound(equipmentId) to null
        if (EquipmentLevelSystem.isMaxLevel(instance.level)) {
            return AppError.Domain.Disciple.SlotInvalid("装备已满级") to null
        }

        val stoneCost = EquipmentLevelSystem.spiritStonesCost(instance.level, instance.rarity)
        val materialCost = EquipmentLevelSystem.beastMaterialCost(instance.level)

        // 材料校验（先验后扣，失败不扣材料）
        if (gameData.spiritStones < stoneCost) {
            return AppError.Domain.Disciple.SlotInvalid("灵石不足（需 $stoneCost）") to null
        }
        val materialPlan = planBeastMaterialDeduct(materialCost) ?: run {
            return AppError.Domain.Disciple.SlotInvalid("兽材不足（需 $materialCost 件）") to null
        }

        // 扣材料
        gameData = gameData.copy(spiritStones = gameData.spiritStones - stoneCost)
        applyBeastMaterialDeduct(materialPlan)

        // 经验推进：升级材料消耗直接折算为该级经验（满足即升级，节点逐级判定）
        val expGain = EquipmentLevelSystem.expRequired(instance.level, instance.rarity)
        val (newLevel, newExp) = EquipmentLevelSystem.advance(
            instance.level, instance.exp, expGain, instance.rarity
        )

        // 强化节点：升级动作完成时判定（Lv3/6/…/30 共 10 次；Lv30 封顶后不再触发）
        var affix = instance.growth.affix
        if (EquipmentLevelSystem.triggersReinforcement(newLevel)) {
            affix = reinforceAffix(affix)
        }

        val updated = instance.copy(
            growth = instance.growth.copy(level = newLevel, exp = newExp, affix = affix)
        )
        equipmentInstances.update(equipmentId) { updated }
        DomainLog.d(TAG, "upgrade: $equipmentId → Lv$newLevel (stones=$stoneCost, beasts=$materialCost)")
        return null to updated
    }

    /** 强化节点：随机挑一条副词条 +1 次（RngPartition.EQUIPMENT；恒 3 条去重面） */
    private fun MutableGameState.reinforceAffix(affix: EquipAffixSet): EquipAffixSet {
        if (affix.subStats.isEmpty()) return affix
        val rng = rngManager.getRng(RngPartition.EQUIPMENT)
        val index = rng.nextInt(affix.subStats.size)
        val rolls = affix.subRolls.toMutableList()
        while (rolls.size <= index) rolls.add(1)
        rolls[index] = (rolls[index] + 1).coerceAtMost(EquipLevelCurve.MAX_SUB_ROLLS)
        return affix.copy(subRolls = rolls)
    }

    // ==================== 分解 ====================

    /** 分解一件装备（返还 50% 累计消耗；锁/已穿戴拒绝） */
    fun dismantleEquipment(equipmentId: String): DomainResult<Unit> {
        var error: AppError.Domain? = AppError.Domain.Disciple.NotFound(equipmentId)
        stateStore.update {
            val instance = equipmentInstances.get(equipmentId)
            if (instance == null) {
                error = AppError.Domain.Disciple.NotFound(equipmentId); return@update
            }
            if (instance.isLocked) {
                error = AppError.Domain.Disciple.SlotInvalid("已锁定的装备不可分解"); return@update
            }
            if (instance.isEquipped) {
                error = AppError.Domain.Disciple.SlotInvalid("已穿戴的装备须先卸下再分解"); return@update
            }

            val (stones, beasts) = EquipmentLevelSystem.dismantleRefund(instance.rarity, instance.level)
            gameData = gameData.copy(spiritStones = gameData.spiritStones + stones)
            if (beasts > 0) grantBeastMaterials(beasts)
            // 弟子储物袋内的同件条目一并清除（防分解后取回复活）
            stripBagEntry(instance.id)
            equipmentInstances = equipmentInstances.filter { it.id != equipmentId }
            DomainLog.d(TAG, "dismantle: $equipmentId refund stones=$stones beasts=$beasts")
            error = null
        }
        val finalError = error
        return if (finalError == null) DomainResult.Success(Unit) else DomainResult.Failure(finalError)
    }

    /** 清除弟子储物袋内该装备条目（分解防复活） */
    private fun MutableGameState.stripBagEntry(equipmentId: String) {
        for (id in discipleTables.ids) {
            val bag = discipleTables.storageBagItems[id]
            if (bag != null && bag.any { it.itemId == equipmentId }) {
                discipleTables.storageBagItems[id] = bag.filterNot { it.itemId == equipmentId }
            }
        }
    }

    // ==================== 兽材扣减/返还（双端同口径） ====================

    /**
     * 兽材扣减计划：BEAST_* 全类目合并计数，按 (rarity 升序, id 升序) 逐堆叠扣。
     * @return 不足返 null（不落任何改动）；充足返回扣减计划
     */
    private fun MutableGameState.planBeastMaterialDeduct(need: Int): List<Pair<String, Int>>? {
        val stacks = materials.items
            .filter { it.category != MaterialCategory.BEAST_HIDE || true } // 全类目均为兽材
            .sortedWith(compareBy({ it.rarity }, { it.id }))
        val plan = mutableListOf<Pair<String, Int>>()
        var remain = need
        for (stack in stacks) {
            if (remain <= 0) break
            val take = minOf(stack.quantity, remain)
            plan.add(stack.id to take)
            remain -= take
        }
        return if (remain > 0) null else plan
    }

    private fun MutableGameState.applyBeastMaterialDeduct(plan: List<Pair<String, Int>>) {
        for ((id, take) in plan) {
            val stack = materials.get(id) ?: continue
            val newQty = stack.quantity - take
            if (newQty <= 0) materials.remove(id)
            else materials.update(id) { it.copy(quantity = newQty) }
        }
    }

    /** 兽材返还：按最低稀有度兽材模板铸入（BEAST_HIDE 类目、rarity=1） */
    private fun MutableGameState.grantBeastMaterials(count: Int) {
        if (count <= 0) return
        val existing = materials.items.firstOrNull { it.rarity == 1 }
        if (existing != null) {
            materials.update(existing.id) { it.copy(quantity = it.quantity + count) }
        } else {
            val material = Material(
                name = "凡兽材",
                rarity = 1,
                category = MaterialCategory.BEAST_HIDE,
                quantity = count,
                description = "装备分解返还的兽材"
            )
            materials = materials + material
        }
    }
}

